/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.validation.validator;

import io.micronaut.aop.Intercepted;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.context.ExecutionHandleLocator;
import io.micronaut.context.annotation.ConfigurationReader;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.BeanInstantiationException;
import io.micronaut.core.annotation.AnnotatedElement;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ArgumentValue;
import io.micronaut.core.type.MutableArgumentValue;
import io.micronaut.core.type.ReturnType;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.InjectionPoint;
import io.micronaut.inject.ProxyBeanDefinition;
import io.micronaut.inject.annotation.AnnotatedElementValidator;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.validation.BeanDefinitionValidator;
import io.micronaut.validation.annotation.ValidatedElement;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import io.micronaut.validation.validator.constraints.ConstraintValidatorRegistry;
import io.micronaut.validation.validator.constraints.InternalConstraintValidatorFactory;
import io.micronaut.validation.validator.extractors.ValueExtractorDefinition;
import io.micronaut.validation.validator.extractors.ValueExtractorRegistry;
import jakarta.inject.Singleton;
import jakarta.validation.ClockProvider;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.Payload;
import jakarta.validation.TraversableResolver;
import jakarta.validation.UnexpectedTypeException;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ValidateUnwrappedValue;
import jakarta.validation.valueextraction.ValueExtractor;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Default implementation of the {@link Validator} interface.
 *
 * @author graemerocher
 * @author Andriy Dmytruk
 * @since 1.2
 */
@Singleton
@Primary
@Requires(property = ValidatorConfiguration.ENABLED, value = StringUtils.TRUE, defaultValue = StringUtils.TRUE)
public class DefaultValidator implements
    Validator, ExecutableMethodValidator, ReactiveValidator, AnnotatedElementValidator, BeanDefinitionValidator {

    private static final ValueExtractor<Object[]> LEGACY_ARRAY_EXTRACTOR = (originalValue, receiver) -> {
        int i = 0;
        for (Object item : originalValue) {
            receiver.indexedValue("<array element>", i++, item);
        }
    };

    final MessageInterpolator messageInterpolator;

    private final ConstraintValidatorRegistry constraintValidatorRegistry;
    private final ClockProvider clockProvider;
    private final ValueExtractorRegistry valueExtractorRegistry;
    private final TraversableResolver traversableResolver;
    private final ExecutionHandleLocator executionHandleLocator;
    private final ConversionService conversionService;
    private final BeanIntrospector beanIntrospector;
    private final InternalConstraintValidatorFactory constraintValidatorFactory;

    /**
     * Default constructor.
     *
     * @param configuration The validator configuration
     */
    public DefaultValidator(@NonNull ValidatorConfiguration configuration) {
        requireNonNull("configuration", configuration);
        this.constraintValidatorRegistry = configuration.getConstraintValidatorRegistry();
        this.clockProvider = configuration.getClockProvider();
        this.valueExtractorRegistry = configuration.getValueExtractorRegistry();
        this.traversableResolver = configuration.getTraversableResolver();
        this.executionHandleLocator = configuration.getExecutionHandleLocator();
        this.messageInterpolator = configuration.getMessageInterpolator();
        this.conversionService = configuration.getConversionService();
        this.beanIntrospector = configuration.getBeanIntrospector();
        this.constraintValidatorFactory = (InternalConstraintValidatorFactory) configuration.getConstraintValidatorFactory();
    }

    /**
     * @return The clock provider
     */
    ClockProvider getClockProvider() {
        return clockProvider;
    }

    /**
     * @return The bean introspector
     */
    public BeanIntrospector getBeanIntrospector() {
        return beanIntrospector;
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validate(@NonNull T object, @Nullable Class<?>... groups) {
        requireNonNull("object", object);
        final BeanIntrospection<T> introspection = getBeanIntrospection(object);
        if (introspection == null) {
            throw new ValidationException("Bean introspection not found for the class: " + object.getClass());
        }
        return validate(introspection, object, BeanValidationContext.fromGroups(groups));
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validate(T object, BeanValidationContext validationContext) {
        requireNonNull("object", object);
        final BeanIntrospection<T> introspection = getBeanIntrospection(object);
        if (introspection == null) {
            throw new ValidationException("Bean introspection not found for the class: " + object.getClass());
        }
        return validate(introspection, object, validationContext);
    }

    /**
     * Validate the given introspection and object.
     *
     * @param introspection The introspection
     * @param object        The object
     * @param groups        The groups
     * @param <T>           The object type
     * @return The constraint violations
     */
    @Override
    @NonNull
    public <T> Set<ConstraintViolation<T>> validate(@NonNull BeanIntrospection<T> introspection,
                                                    @NonNull T object,
                                                    @NonNull Class<?>... groups) {
        return validate(
            introspection,
            object,
            BeanValidationContext.fromGroups(groups)
        );
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validate(BeanIntrospection<T> introspection, T object, BeanValidationContext context) {
        if (introspection == null) {
            throw new ValidationException("Passed object [" + object + "] cannot be introspected. Please annotate with @Introspected");
        }
        DefaultConstraintValidatorContext<T> constraintValidatorContext = new DefaultConstraintValidatorContext<>(
            this,
            introspection,
            object,
            context
        );
        doValidate(constraintValidatorContext, introspection, object);
        return constraintValidatorContext.getOverallViolations();
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateProperty(@NonNull T object,
                                                            @NonNull String propertyName,
                                                            @NonNull Class<?>... groups) {
        return validateProperty(
            object,
            propertyName,
            BeanValidationContext.fromGroups(groups)
        );
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName, BeanValidationContext context) {
        requireNonNull("object", object);
        requireNonEmpty("propertyName", propertyName);
        context = context != null ? context : BeanValidationContext.DEFAULT;
        final BeanIntrospection<T> introspection = getBeanIntrospection(object);
        if (introspection == null) {
            throw new ValidationException("Passed object [" + object + "] cannot be introspected. Please annotate with @Introspected");
        }

        final Optional<BeanProperty<T, Object>> property = introspection.getProperty(propertyName);
        if (property.isEmpty()) {
            throw new IllegalArgumentException("Cannot find property with name: " + property);
        }

        DefaultConstraintValidatorContext<T> constraintValidationContext = new DefaultConstraintValidatorContext<>(this, introspection, object, context);

        for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : constraintValidationContext.findGroupSequences(introspection)) {
            try (DefaultConstraintValidatorContext.GroupsValidation validation = constraintValidationContext.withGroupSequence(groupSequence)) {
                visitProperty(constraintValidationContext, object, property.get(), false);
                if (validation.isFailed()) {
                    return Collections.unmodifiableSet(constraintValidationContext.getOverallViolations());
                }
            }
        }

        return Collections.unmodifiableSet(constraintValidationContext.getOverallViolations());
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateValue(@NonNull Class<T> beanType,
                                                         @NonNull String propertyName,
                                                         @Nullable Object value,
                                                         @NonNull Class<?>... groups) {
        requireNonNull("groups", groups);
        return validateValue(beanType, propertyName, value, BeanValidationContext.fromGroups(groups));
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateValue(Class<T> beanType, String propertyName, Object value, BeanValidationContext context) {
        requireNonNull("beanType", beanType);
        requireNonEmpty("propertyName", propertyName);

        final BeanIntrospection<T> introspection = getBeanIntrospection(beanType);
        if (introspection == null) {
            throw new ValidationException("Passed bean type [" + beanType + "] cannot be introspected. Please annotate with @Introspected");
        }

        final BeanProperty<T, Object> beanProperty = introspection.getProperty(propertyName)
            .orElseThrow(() -> new IllegalArgumentException("No property [" + propertyName + "] found on type: " + beanType));

        DefaultConstraintValidatorContext<T> constraintContext = new DefaultConstraintValidatorContext<>(this, introspection, null, context);

        try (ValidationPath.ContextualPath ignored = constraintContext.getCurrentPath().addPropertyNode(beanProperty.getName())) {
            if (isNotReachable(constraintContext, null)) {
                return Collections.unmodifiableSet(constraintContext.getOverallViolations());
            }
            for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : constraintContext.findGroupSequences(introspection)) {
                try (DefaultConstraintValidatorContext.GroupsValidation validation = constraintContext.withGroupSequence(groupSequence)) {

                    visitElement(constraintContext, null, beanProperty.asArgument(), beanProperty.asArgument().getAnnotationMetadata(), value, false);

                    if (validation.isFailed()) {
                        return Collections.unmodifiableSet(constraintContext.getOverallViolations());
                    }
                }
            }
        }

        return Collections.unmodifiableSet(constraintContext.getOverallViolations());
    }

    @NonNull
    @Override
    public Set<String> validatedAnnotatedElement(@NonNull AnnotatedElement element, @Nullable Object value) {
        requireNonNull("element", element);
        if (!element.getAnnotationMetadata().hasStereotype(Constraint.class)) {
            return Collections.emptySet();
        }

        final DefaultConstraintValidatorContext<Object> context = new DefaultConstraintValidatorContext<>(this, null, value, BeanValidationContext.DEFAULT);

        Argument<Object> type = value != null ? Argument.of((Class<Object>) value.getClass(), element.getAnnotationMetadata()) : Argument.OBJECT_ARGUMENT;

        boolean canCascade = true;
        try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addPropertyNode(element.getName())) {
            for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : context.findGroupSequences()) {
                try (DefaultConstraintValidatorContext.GroupsValidation validation = context.withGroupSequence(groupSequence)) {

                    visitElement(context, element, type, value, canCascade);

                    if (validation.isFailed()) {
                        return context.getOverallViolations().stream().map(ConstraintViolation::getMessage).collect(Collectors.toUnmodifiableSet());
                    }
                }
                canCascade = false;
            }
        }

        return context.getOverallViolations().stream().map(ConstraintViolation::getMessage).collect(Collectors.toUnmodifiableSet());
    }

    @NonNull
    @Override
    public <T> T createValid(@NonNull Class<T> beanType, Object... arguments) throws ConstraintViolationException {
        requireNonNull("type", beanType);

        final BeanIntrospection<T> introspection = getBeanIntrospection(beanType);
        if (introspection == null) {
            throw new ValidationException("Passed bean type [" + beanType + "] cannot be introspected. Please annotate with @Introspected");
        }

        final Set<ConstraintViolation<T>> constraintViolations = validateConstructorParameters(introspection, arguments);
        if (!constraintViolations.isEmpty()) {
            throw new ConstraintViolationException(constraintViolations);
        }

        final T instance = introspection.instantiate(arguments);
        final Set<ConstraintViolation<T>> errors = validate(introspection, instance);
        if (errors.isEmpty()) {
            return instance;
        }
        throw new ConstraintViolationException(errors);
    }

    @Override
    public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
        if (clazz == null) {
            throw new IllegalArgumentException();
        }
        return beanIntrospector.findIntrospection(clazz)
            .map((Function<BeanIntrospection<?>, BeanDescriptor>) IntrospectedBeanDescriptor::new)
            .orElseGet(() -> new EmptyDescriptor(clazz));
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        throw new UnsupportedOperationException("Validator unwrapping not supported by this implementation");
    }

    @Override
    @NonNull
    public ExecutableMethodValidator forExecutables() {
        return this;
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateParameters(@NonNull T object,
                                                              @NonNull ExecutableMethod method,
                                                              @NonNull Object[] parameterValues,
                                                              @NonNull Class<?>... groups) {
        requireNonNull("groups", groups);
        return validateParameters(object, method, parameterValues, BeanValidationContext.fromGroups(groups));
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateParameters(T object, ExecutableMethod method, @NonNull Object[] parameterValues, BeanValidationContext validationContext) {
        requireNonNull("parameterValues", parameterValues);
        requireNonNull("object", object);
        requireNonNull("method", method);
        requireNonNull("context", validationContext);
        final Argument<?>[] arguments = method.getArguments();
        final int argLen = arguments.length;
        if (argLen != parameterValues.length) {
            throw new IllegalArgumentException("The method parameter array must have exactly " + argLen + " elements.");
        }

        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(this, null, object, validationContext);
        try (DefaultConstraintValidatorContext.ValidationCloseable ignored1 = context.withExecutableParameterValues(parameterValues)) {
            try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addMethodNode(method)) {
                AnnotationMetadata methodAnnotationMetadata = method.getAnnotationMetadata().getDeclaredMetadata();
                validateParametersInternal(context, object, methodAnnotationMetadata, parameterValues, arguments, argLen);
            }
        }
        return Collections.unmodifiableSet(context.getOverallViolations());
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateParameters(@NonNull T object,
                                                              @NonNull ExecutableMethod method,
                                                              @NonNull Collection<MutableArgumentValue<?>> argumentValues,
                                                              @NonNull Class<?>... groups) {
        requireNonNull("object", object);
        requireNonNull("method", method);
        requireNonNull("parameterValues", argumentValues);
        requireNonNull("groups", groups);

        final Argument<?>[] arguments = method.getArguments();
        final int argLen = arguments.length;
        if (argLen != argumentValues.size()) {
            throw new IllegalArgumentException("The method parameter array must have exactly " + argLen + " elements.");
        }

        Object[] parameters = argumentValues.stream().map(ArgumentValue::getValue).toArray();

        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(this, null, object, BeanValidationContext.fromGroups(groups));
        try (DefaultConstraintValidatorContext.ValidationCloseable ignored1 = context.withExecutableParameterValues(parameters)) {
            try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addMethodNode(method)) {
                AnnotationMetadata methodAnnotationMetadata = method.getAnnotationMetadata().getDeclaredMetadata();
                validateParametersInternal(context, object, methodAnnotationMetadata, parameters, arguments, argLen);
            }
        }
        return Collections.unmodifiableSet(context.getOverallViolations());
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateParameters(@NonNull T object,
                                                              @NonNull Method method,
                                                              @NonNull Object[] parameterValues,
                                                              @NonNull Class<?>... groups) {
        requireNonNull("method", method);
        requireNonNull("groups", groups);

        return executionHandleLocator.findExecutableMethod(
                method.getDeclaringClass(),
                method.getName(),
                method.getParameterTypes()
            ).map(executableMethod -> validateParameters(object, executableMethod, parameterValues, groups))
            .orElse(Collections.emptySet());
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateReturnValue(@NonNull T object,
                                                               @NonNull Method method,
                                                               @Nullable Object returnValue,
                                                               @NonNull Class<?>... groups) {
        requireNonNull("method", method);
        requireNonNull("object", object);
        requireNonNull("groups", groups);

        return executionHandleLocator.findExecutableMethod(
                method.getDeclaringClass(),
                method.getName(),
                method.getParameterTypes()
            ).map(executableMethod -> validateReturnValue(object, executableMethod, returnValue, groups))
            .orElse(Collections.emptySet());
    }

    @Override
    public @NonNull <T> Set<ConstraintViolation<T>> validateReturnValue(@NonNull T bean,
                                                                        @NonNull ExecutableMethod<?, Object> executableMethod,
                                                                        @Nullable Object returnValue,
                                                                        @NonNull Class<?>... groups) {
        requireNonNull("groups", groups);

        return validateReturnValue(
            bean,
            executableMethod,
            returnValue,
            BeanValidationContext.fromGroups(groups)
        );
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateReturnValue(T bean, ExecutableMethod<?, Object> executableMethod, Object returnValue, BeanValidationContext validationContext) {
        final ReturnType<Object> returnType = executableMethod.getReturnType();
        final DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(this, null, bean, validationContext);

        try (DefaultConstraintValidatorContext.ValidationCloseable ignored1 = context.withExecutableReturnValue(returnValue)) {
            try (ValidationPath.ContextualPath ignored2 = context.getCurrentPath().addMethodNode(executableMethod)) {
                try (ValidationPath.ContextualPath ignored3 = context.getCurrentPath().addReturnValueNode()) {
                    List<DefaultConstraintValidatorContext.ValidationGroup> groupSequences;
                    if (bean == null) {
                        groupSequences = context.findGroupSequences();
                    } else {
                        BeanIntrospection<T> beanIntrospection = getBeanIntrospection(bean);
                        if (beanIntrospection == null) {
                            groupSequences = context.findGroupSequences();
                        } else {
                            groupSequences = context.findGroupSequences(beanIntrospection);
                        }
                    }

                    boolean canCascade = true;
                    for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : groupSequences) {
                        try (DefaultConstraintValidatorContext.GroupsValidation validation = context.withGroupSequence(groupSequence)) {
                            // Strip class annotations
                            AnnotationMetadata returnAm = returnType.asArgument().getAnnotationMetadata();
                            if (returnAm instanceof AnnotationMetadataHierarchy annotationMetadataHierarchy) {
                                if (returnAm.getDeclaredMetadata() instanceof AnnotationMetadataHierarchy) {
                                    returnAm = new AnnotationMetadataHierarchy(
                                        annotationMetadataHierarchy.getRootMetadata(),
                                        annotationMetadataHierarchy.getDeclaredMetadata().getDeclaredMetadata()
                                    );
                                } else {
                                    returnAm = annotationMetadataHierarchy.getDeclaredMetadata();
                                }
                            }
                            visitElement(context, bean, returnType.asArgument(), returnAm, returnValue, canCascade, false);

                            if (validation.isFailed()) {
                                return context.getOverallViolations();
                            }
                        }
                        canCascade = false;
                    }
                }
            }
        }

        return context.getOverallViolations();
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(@NonNull Constructor<? extends T> constructor,
                                                                         @NonNull Object[] parameterValues,
                                                                         @NonNull Class<?>... groups) {
        requireNonNull("constructor", constructor);
        requireNonNull("groups", groups);

        final Class<? extends T> declaringClass = constructor.getDeclaringClass();
        final BeanIntrospection<? extends T> introspection = getBeanIntrospection(declaringClass);
        return validateConstructorParameters(introspection, parameterValues);
    }

    @Override
    @NonNull
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(@NonNull BeanIntrospection<? extends T> introspection,
                                                                         @NonNull Object[] parameterValues,
                                                                         @NonNull Class<?>... groups) {
        requireNonNull("introspection", introspection);
        requireNonNull("groups", groups);

        final Class<? extends T> beanType = introspection.getBeanType();
        final Argument<?>[] constructorArguments = introspection.getConstructorArguments();
        return validateConstructorParameters(beanType, constructorArguments, parameterValues, groups);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(Class<? extends T> beanType,
                                                                         Argument<?>[] constructorArguments,
                                                                         @NonNull Object[] parameterValues,
                                                                         @NonNull Class<?>[] groups) {
        requireNonNull("groups", groups);

        return validateConstructorParameters(
            beanType,
            constructorArguments,
            parameterValues,
            BeanValidationContext.fromGroups(groups)
        );
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(Class<? extends T> beanType, @NonNull Argument<?>[] constructorArguments, @NonNull Object[] parameterValues, BeanValidationContext validationContext) {
        parameterValues = parameterValues != null ? parameterValues : ArrayUtils.EMPTY_OBJECT_ARRAY;
        final int argLength = constructorArguments.length;
        if (parameterValues.length != argLength) {
            throw new IllegalArgumentException("Expected exactly [" + argLength + "] constructor arguments");
        }
        DefaultConstraintValidatorContext<T> context = (DefaultConstraintValidatorContext<T>) new DefaultConstraintValidatorContext<>(this, null, beanType, validationContext);
        try (DefaultConstraintValidatorContext.ValidationCloseable ignored1 = context.withExecutableParameterValues(parameterValues)) {
            try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addConstructorNode(beanType.getSimpleName(), constructorArguments)) {
                validateParametersInternal(context, null, AnnotationMetadata.EMPTY_METADATA, parameterValues, constructorArguments, argLength);
            }
        }
        return Collections.unmodifiableSet(context.getOverallViolations());
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorReturnValue(@NonNull Constructor<? extends T> constructor,
                                                                          @NonNull T createdObject,
                                                                          @NonNull Class<?>... groups) {
        requireNonNull("groups", groups);

        return validate(createdObject, groups);
    }

    @NonNull
    @Override
    public <T> Publisher<T> validatePublisher(@NonNull ReturnType<?> returnType,
                                              @NonNull Publisher<T> publisher,
                                              @NonNull Class<?>... groups) {
        requireNonNull("publisher", publisher);
        requireNonNull("returnType", returnType);
        requireNonNull("groups", groups);

        if (returnType.getTypeParameters().length == 0) {
            return publisher;
        }
        Argument<Object> typeParameter = returnType.getTypeParameters()[0];
        Argument<Publisher<T>> publisherArgument = Argument.of((Class<Publisher<T>>) publisher.getClass());

        Publisher<Object> output;
        if (Publishers.isSingle(returnType.getType())) {
            output = Mono.from(publisher).flatMap(value -> {
                Set<? extends ConstraintViolation<?>> violations = validatePublisherValue(publisherArgument, publisher, groups, typeParameter, value, true);
                return violations.isEmpty() ? Mono.just(value) :
                    Mono.error(new ConstraintViolationException(violations));
            });
        } else {
            output = Flux.from(publisher).flatMap(value -> {
                Set<? extends ConstraintViolation<?>> violations = validatePublisherValue(publisherArgument, publisher, groups, typeParameter, value, true);
                return violations.isEmpty() ? Flux.just(value) :
                    Flux.error(new ConstraintViolationException(violations));
            });
        }
        Class<?> returnClass = returnType.getType();
        if (!Publisher.class.isAssignableFrom(returnClass)) {
            return (Publisher<T>) output;
        }
        return Publishers.convertPublisher(conversionService, output, (Class<Publisher>) returnClass);
    }

    /**
     * A method used inside the {@link #validatePublisher} method.
     */
    private <T, E> Set<? extends ConstraintViolation<?>> validatePublisherValue(Argument<T> publisherArgument,
                                                                                @NonNull T publisher,
                                                                                Class<?>[] groups,
                                                                                Argument<E> valueArgument,
                                                                                E value,
                                                                                boolean canCascade
    ) {
        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(this, null, publisher, BeanValidationContext.fromGroups(groups));
        try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addReturnValueNode()) {
            try (ValidationPath.ContextualPath ignored1 = context.getCurrentPath().addContainerElementNode("<publisher element>",
                ValidationPath.DefaultContainerContext.ofIterableContainer(publisherArgument.getType()))) {
                for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : context.findGroupSequences()) {
                    try (DefaultConstraintValidatorContext.GroupsValidation ignore = context.withGroupSequence(groupSequence)) {
                        visitElement(context, publisher, valueArgument, value, canCascade);
                    }
                }
            }
        }
        return context.getOverallViolations();
    }

    @NonNull
    @Override
    public <T> CompletionStage<T> validateCompletionStage(@NonNull CompletionStage<T> completionStage,
                                                          @NonNull Argument<T> argument,
                                                          @NonNull Class<?>... groups) {
        requireNonNull("completionStage", completionStage);
        requireNonNull("groups", groups);

        DefaultConstraintValidatorContext<Object> context = new DefaultConstraintValidatorContext<>(this, null, null, BeanValidationContext.fromGroups(groups));
        for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : context.findGroupSequences()) {
            try (DefaultConstraintValidatorContext.GroupsValidation ignore = context.withGroupSequence(groupSequence)) {
                return instrumentCompletionStage(context, completionStage, argument, true);
            }
        }
        return completionStage;
    }

    @Override
    public <T> void validateBeanArgument(@NonNull BeanResolutionContext resolutionContext,
                                         @NonNull InjectionPoint injectionPoint,
                                         @NonNull Argument<T> argument,
                                         int index,
                                         @Nullable T value) throws BeanInstantiationException {
        final AnnotationMetadata annotationMetadata = argument.getAnnotationMetadata();
        final boolean hasValid = annotationMetadata.hasStereotype(Valid.class);
        final boolean hasConstraint = annotationMetadata.hasStereotype(Constraint.class);

        if (!hasConstraint && !hasValid) {
            return;
        }

        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(this, null, value, BeanValidationContext.DEFAULT);

        final Class<?> rootClass = injectionPoint.getDeclaringBean().getBeanType();

        boolean canCascade = true;
        try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addConstructorNode(
            rootClass.getName(), injectionPoint.getDeclaringBean().getConstructor().getArguments())) {
            try (ValidationPath.ContextualPath ignored1 = context.getCurrentPath().addPropertyNode(argument.getName())) {
                try (DefaultConstraintValidatorContext.ValidationCloseable ignore4 = context.convertGroups(argument.getAnnotationMetadata())) {
                    for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : context.findGroupSequences()) {
                        try (DefaultConstraintValidatorContext.GroupsValidation validation = context.withGroupSequence(groupSequence)) {

                            visitElement(context, null, argument, value, canCascade);

                            if (validation.isFailed()) {
                                failOnError(resolutionContext, context.getOverallViolations(), rootClass);
                            }
                        }
                        canCascade = false;
                    }
                }
            }
        }

        failOnError(resolutionContext, context.getOverallViolations(), rootClass);
    }

    @Override
    public <T> void validateBean(@NonNull BeanResolutionContext resolutionContext,
                                 @NonNull BeanDefinition<T> definition,
                                 @NonNull T bean) throws BeanInstantiationException {
        Class<T> beanType;
        if (definition instanceof ProxyBeanDefinition<?> proxyBeanDefinition) {
            beanType = (Class<T>) proxyBeanDefinition.getTargetType();
        } else {
            beanType = definition.getBeanType();
        }
        final BeanIntrospection<T> introspection = getBeanIntrospection(bean, beanType);
        if (introspection != null) {
            Set<ConstraintViolation<T>> errors = validate(introspection, bean);
            failOnError(resolutionContext, errors, beanType);
        } else if (bean instanceof Intercepted && definition.hasStereotype(ConfigurationReader.class)) {
            final Collection<ExecutableMethod<T, ?>> executableMethods = definition.getExecutableMethods();
            if (CollectionUtils.isEmpty(executableMethods)) {
                return;
            }
            final DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(this, null, bean, BeanValidationContext.DEFAULT);
            final Class<?>[] interfaces = beanType.getInterfaces();
            String constructorName;
            if (ArrayUtils.isNotEmpty(interfaces)) {
                constructorName = interfaces[0].getSimpleName();
            } else {
                constructorName = beanType.getSimpleName();
            }
            try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addConstructorNode(constructorName)) {
                for (ExecutableMethod<T, ?> executableMethod : executableMethods) {
                    if (executableMethod.hasAnnotation(Property.class)) {
                        final boolean hasConstraint = executableMethod.hasStereotype(Constraint.class);
                        final boolean isValid = executableMethod.hasStereotype(Valid.class);
                        if (hasConstraint || isValid) {
                            final Object value = executableMethod.invoke(bean);

                            final ReturnType<Object> returnType = (ReturnType<Object>) executableMethod.getReturnType();

                            try (ValidationPath.ContextualPath ignored1 = context.getCurrentPath().addPropertyNode(executableMethod.getName())) {
                                try (DefaultConstraintValidatorContext.ValidationCloseable ignore2 = context.convertGroups(executableMethod.getAnnotationMetadata())) {

                                    boolean canCascade = true;
                                    for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : context.findGroupSequences()) {
                                        try (DefaultConstraintValidatorContext.GroupsValidation validation = context.withGroupSequence(groupSequence)) {

                                            visitElement(context, bean, returnType.asArgument(), value, canCascade);

                                            if (validation.isFailed()) {
                                                failOnError(resolutionContext, context.getOverallViolations(), beanType);
                                            }
                                        }
                                        canCascade = false;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            failOnError(resolutionContext, context.getOverallViolations(), beanType);
        } else {
            throw new BeanInstantiationException(resolutionContext, "Cannot validate bean [" + beanType.getName() + "]. No bean introspection present. Please add @Introspected.");
        }
    }

    /**
     * looks up a bean introspection for the given object by instance's class or defined class.
     *
     * @param object       The object, never null
     * @param definedClass The defined class of the object, never null
     * @param <T>          The introspection type
     * @return The introspection or null
     */
    @SuppressWarnings({"WeakerAccess", "unchecked"})
    @Nullable
    protected <T> BeanIntrospection<T> getBeanIntrospection(@NonNull T object,
                                                            @NonNull Class<T> definedClass) {
        if (object == null) {
            return null;
        }
        return beanIntrospector.findIntrospection((Class<T>) object.getClass())
            .orElseGet(() -> beanIntrospector.findIntrospection(definedClass).orElse(null));
    }

    /**
     * Looks up a bean introspection for the given object.
     *
     * @param object The object, never null
     * @param <T>    The introspection type
     * @return The introspection or null
     */
    @SuppressWarnings({"WeakerAccess", "unchecked"})
    @Nullable
    protected <T> BeanIntrospection<T> getBeanIntrospection(@NonNull T object) {
        if (object == null) {
            return null;
        }
        if (object instanceof Class) {
            return getBeanIntrospection((Class<T>) object);
        }
        return beanIntrospector.findIntrospection((Class<T>) object.getClass()).orElse(null);
    }

    /**
     * Looks up a bean introspection for the given object.
     *
     * @param type The object type
     * @param <T>  The introspection type
     * @return The introspection or null
     */
    @SuppressWarnings({"WeakerAccess"})
    @Nullable
    protected <T> BeanIntrospection<T> getBeanIntrospection(@NonNull Class<T> type) {
        return beanIntrospector.findIntrospection(type).orElse(null);
    }

    private <R, E> void instrumentPublisherArgumentWithValidation(@NonNull DefaultConstraintValidatorContext<R> context,
                                                                  @NonNull Object[] argumentValues,
                                                                  int argumentIndex,
                                                                  @NonNull Argument<E> publisherArgument,
                                                                  E parameterValue,
                                                                  boolean canCascade) {
        final Publisher<?> publisher = Publishers.convertPublisher(conversionService, parameterValue, Publisher.class);
        DefaultConstraintValidatorContext<R> valueContext = context.copy();

        Publisher<?> objectPublisher;
        if (publisherArgument.isSpecifiedSingle()) {
            objectPublisher = Mono.from(publisher)
                .flatMap(value -> {

                    validatePublishedValue(valueContext, publisherArgument, parameterValue, value, canCascade);

                    return valueContext.getOverallViolations().isEmpty() ? Mono.just(value) :
                        Mono.error(new ConstraintViolationException(valueContext.getOverallViolations()));
                });
        } else {
            objectPublisher = Flux.from(publisher).flatMap(value -> {

                validatePublishedValue(valueContext, publisherArgument, parameterValue, value, canCascade);

                return valueContext.getOverallViolations().isEmpty() ? Flux.just(value) :
                    Flux.error(new ConstraintViolationException(valueContext.getOverallViolations()));
            });
        }
        argumentValues[argumentIndex] = Publishers.convertPublisher(conversionService, objectPublisher, publisherArgument.getType());
    }

    private <R, E> void validatePublishedValue(@NonNull DefaultConstraintValidatorContext<R> context,
                                               @NonNull Argument<E> publisherArgument,
                                               E value,
                                               @NonNull Object publisherInstance,
                                               boolean canCascade) {
        // noinspection unchecked
        Argument<Object>[] typeParameters = publisherArgument.getTypeParameters();

        if (typeParameters.length == 0) {
            // No validation if no parameters
            return;
        }
        Argument<Object> valueArgument = typeParameters[0];

        try (ValidationPath.ContextualPath ignored1 = context.getCurrentPath()
            .addContainerElementNode("<publisher element>", ValidationPath.DefaultContainerContext.ofIterableContainer(value.getClass()))) {
            visitElement(context, context.getRootBean(), valueArgument, publisherInstance, canCascade);
        }
    }

    /**
     * Processes a method argument that is a completion stage. Since the argument cannot be validated
     * at this exact time, the validation is applied to the completion stage.
     */
    private <T, E> void instrumentCompletionStageArgumentWithValidation(@NonNull DefaultConstraintValidatorContext<T> context,
                                                                        @NonNull Object[] argumentValues,
                                                                        int argumentIndex,
                                                                        @NonNull Argument<E> completionStageArgument,
                                                                        E parameterValue,
                                                                        boolean canCascade) {
        final CompletionStage<Object> completionStage = (CompletionStage<Object>) parameterValue;

        Argument<Object> valueArgument = (Argument<Object>) completionStageArgument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);

        argumentValues[argumentIndex] = instrumentCompletionStage(context.copy(), completionStage, valueArgument, canCascade);
    }

    private <T, E> CompletionStage<E> instrumentCompletionStage(DefaultConstraintValidatorContext<T> context,
                                                                CompletionStage<E> completionStage,
                                                                Argument<E> argument,
                                                                boolean canCascade) {
        return completionStage.thenApply(value -> {

            try (ValidationPath.ContextualPath ignored1 = context.getCurrentPath()
                .addContainerElementNode("<completion stage element>", ValidationPath.DefaultContainerContext.ofContainer(CompletionStage.class))) {
                visitElement(context, context.getRootBean(), argument, value, canCascade);
            }

            if (!context.getOverallViolations().isEmpty()) {
                throw new ConstraintViolationException(context.getOverallViolations());
            }

            return value;
        });
    }

    private <T> void validateParametersInternal(@NonNull DefaultConstraintValidatorContext<T> context,
                                                @Nullable T bean,
                                                @NonNull AnnotationMetadata methodAnnotationMetadata,
                                                @NonNull Object[] parameters,
                                                @NonNull Argument<?>[] arguments,
                                                int argLen) {

        List<DefaultConstraintValidatorContext.ValidationGroup> groupSequences;
        if (bean == null) {
            groupSequences = context.findGroupSequences();
        } else {
            BeanIntrospection<T> beanIntrospection = getBeanIntrospection(bean);
            if (beanIntrospection == null) {
                groupSequences = context.findGroupSequences();
            } else {
                groupSequences = context.findGroupSequences(beanIntrospection);
            }
        }
        boolean canCascade = true;
        for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : groupSequences) {
            try (DefaultConstraintValidatorContext.GroupsValidation validation = context.withGroupSequence(groupSequence)) {

                if (methodAnnotationMetadata.hasStereotype(Constraint.class)) {
                    try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addCrossParameterNode()) {
                        validateConstrains(context, bean, Argument.of(Object[].class, methodAnnotationMetadata), parameters);
                    }
                }

                for (int parameterIndex = 0; parameterIndex < argLen; parameterIndex++) {
                    Argument<Object> argument = (Argument<Object>) arguments[parameterIndex];
                    if (!argument.getAnnotationMetadata().hasAnnotation(ValidatedElement.class)) {
                        continue;
                    }
                    try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addParameterNode(argument.getName(), parameterIndex)) {
                        try (DefaultConstraintValidatorContext.ValidationCloseable ignore = context.convertGroups(argument.getAnnotationMetadata())) {

                            final Class<Object> parameterType = argument.getType();

                            Object parameterValue = parameters[parameterIndex];

                            final boolean hasValue = parameterValue != null;

                            final boolean isPublisher = hasValue && Publishers.isConvertibleToPublisher(parameterType);
                            if (isPublisher) {
                                instrumentPublisherArgumentWithValidation(context, parameters, parameterIndex, argument, parameterValue, canCascade);
                                continue;
                            }

                            final boolean isCompletionStage = hasValue && CompletionStage.class.isAssignableFrom(parameterType);
                            if (isCompletionStage) {
                                instrumentCompletionStageArgumentWithValidation(context, parameters, parameterIndex, argument, parameterValue, canCascade);
                                continue;
                            }

                            visitElement(context,
                                bean,
                                argument,
                                parameterValue,
                                canCascade,
                                false
                            );
                        }
                    }
                }

                if (validation.isFailed()) {
                    return;
                }
            }
            canCascade = false;
        }
    }

    private <R, T> void doValidate(@NonNull DefaultConstraintValidatorContext<R> context,
                                   @NonNull BeanIntrospection<T> introspection,
                                   @NonNull T object) {
        if (context.isValidated(object)) {
            return;
        }
        boolean canCascade = true;
        try (DefaultConstraintValidatorContext.ValidationCloseable ignore = context.validating(object)) {
            for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : context.findGroupSequences(introspection)) {
                try (DefaultConstraintValidatorContext.GroupsValidation validation = context.withGroupSequence(groupSequence)) {

                    try (ValidationPath.ContextualPath ignore2 = context.getCurrentPath().addBeanNode()) {
                        visitElement(
                            context,
                            object,
                            Argument.of(introspection.getBeanType(), introspection.getAnnotationMetadata()),
                            object,
                            false
                        );
                    }

                    for (BeanProperty<T, Object> property : introspection.getBeanProperties()) {
                        visitProperty(context, object, property, canCascade);
                    }

                    if (validation.isFailed()) {
                        return;
                    }
                }
                canCascade = false;
            }
        }
    }

    private <R, T> void visitProperty(DefaultConstraintValidatorContext<R> context,
                                      T object,
                                      BeanProperty<T, Object> property,
                                      boolean canCascade) {
        if (property.isWriteOnly()) {
            return;
        }

        try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addPropertyNode(property.getName())) {
            if (isNotReachable(context, object) ||
                !context.getValidationContext().isPropertyValidated(object, property)) {
                return;
            }
            try (DefaultConstraintValidatorContext.ValidationCloseable ignore = context.convertGroups(property.getAnnotationMetadata())) {
                Object propertyValue;
                try {
                    propertyValue = property.get(object);
                } catch (Exception e) {
                    throw new ValidationException("Failed to get the value of property: " + property.getName());
                }
                visitElement(
                    context,
                    object,
                    property.asArgument(),
                    propertyValue,
                    canCascade
                );
            }
        }
    }

    private <R, T> boolean isNotReachable(DefaultConstraintValidatorContext<R> context, T object) {
        ValidationPath currentPath = context.getCurrentPath();
        ValidationPath previousPath = currentPath.previousPath();
        try {
            return !traversableResolver.isReachable(
                    object,
                    currentPath.last(),
                    context.getRootClass(),
                    previousPath,
                    ElementType.FIELD
            );
        } catch (Exception e) {
            throw new ValidationException("Cannot call 'isReachable' on traversableResolver: " + traversableResolver, e);
        }
    }

    private <R> boolean canCascade(@NonNull DefaultConstraintValidatorContext<R> context,
                                   Object leftBean) {
        try {
            ValidationPath currentPath = context.getCurrentPath();
            ValidationPath previousPath = currentPath.previousPath();
            return traversableResolver.isCascadable(
                    leftBean,
                    currentPath.last(),
                    context.getRootClass(),
                    previousPath,
                    ElementType.FIELD
            );
        } catch (Exception e) {
            throw new ValidationException("Cannot call 'isCascadable' on traversableResolver: " + traversableResolver, e);
        }
    }

    private <R, E> void visitElement(DefaultConstraintValidatorContext<R> context,
                                     Object bean,
                                     Argument<E> elementArgument,
                                     E elementValue,
                                     boolean canCascade) {
        visitElement(context,
            bean,
            elementArgument,
            elementArgument.getAnnotationMetadata(),
            elementValue,
            canCascade
        );
    }

    private <R, E> void visitElement(DefaultConstraintValidatorContext<R> context,
                                     Object bean,
                                     Argument<E> elementArgument,
                                     AnnotationMetadata annotationMetadata,
                                     E elementValue,
                                     boolean canCascade) {
        visitElement(context,
            bean,
            elementArgument,
            annotationMetadata,
            elementValue,
            canCascade,
            canCascade && annotationMetadata.hasStereotype(Valid.class),
            true
        );
    }

    private <R, E> void visitElement(DefaultConstraintValidatorContext<R> context,
                                     Object bean,
                                     Argument<E> elementArgument,
                                     E elementValue,
                                     boolean canCascade,
                                     boolean needsCanCascadeCheck) {
        AnnotationMetadata annotationMetadata = elementArgument.getAnnotationMetadata();
        visitElement(context,
            bean,
            elementArgument,
            annotationMetadata,
            elementValue,
            canCascade,
            needsCanCascadeCheck
        );
    }

    private <R, E> void visitElement(DefaultConstraintValidatorContext<R> context,
                                     Object bean,
                                     Argument<E> elementArgument,
                                     AnnotationMetadata annotationMetadata,
                                     E elementValue,
                                     boolean canCascade,
                                     boolean needsCanCascadeCheck) {
        visitElement(context,
            bean,
            elementArgument,
            annotationMetadata,
            elementValue,
            canCascade,
            canCascade && annotationMetadata.hasStereotype(Valid.class),
            needsCanCascadeCheck
        );
    }

    private <R, E> void visitElement(DefaultConstraintValidatorContext<R> context,
                                     Object leftBean,
                                     Argument<E> elementArgument,
                                     AnnotationMetadata annotationMetadata,
                                     E elementValue,
                                     boolean canCascade,
                                     boolean hasValid,
                                     boolean needsCanCascadeCheck) {

        List<DefaultConstraintDescriptor<Annotation>> constraints = getConstraints(context, annotationMetadata);

        if (visitContainer(context, leftBean, elementArgument, annotationMetadata, elementValue, constraints, canCascade)) {
            return;
        }

        if (!constraints.isEmpty()) {
            validateConstrains(context, leftBean, elementArgument, elementValue, constraints);
        }

        if (canCascade && hasValid && elementValue != null) {
            try (DefaultConstraintValidatorContext.ValidationCloseable ignore = context.convertGroups(elementArgument.getAnnotationMetadata())) {
                propagateValidation(context, leftBean, elementArgument, elementValue, needsCanCascadeCheck);
            }
        }
    }

    private <R, E> boolean visitContainer(DefaultConstraintValidatorContext<R> context,
                                          Object leftBean,
                                          Argument<E> containerArgument,
                                          AnnotationMetadata annotationMetadata,
                                          E containerValue,
                                          List<DefaultConstraintDescriptor<Annotation>> constraints,
                                          boolean canCascade) {
        if (!isValidated(containerArgument)) {
            return false;
        }

        boolean isLegacyValid = annotationMetadata.hasAnnotation(Valid.class)
            && (Iterable.class.isAssignableFrom(containerArgument.getType())
            || Map.class.isAssignableFrom(containerArgument.getType())
            || Object[].class.isAssignableFrom(containerArgument.getType())
        );

        final List<DefaultConstraintDescriptor<Annotation>> skipUnwrappingConstraints = constraints.stream().filter(c -> c.getValueUnwrapping() == ValidateUnwrappedValue.SKIP).toList();
        final List<DefaultConstraintDescriptor<Annotation>> explicitUnwrappingConstraints = constraints.stream().filter(c -> c.getValueUnwrapping() == ValidateUnwrappedValue.UNWRAP).toList();

        List<ValueExtractorDefinition<E>> valueExtractorDefinitions = valueExtractorRegistry.findValueExtractors(containerArgument.getType());
        if (valueExtractorDefinitions.isEmpty()) {
            if (isLegacyValid && Object[].class.isAssignableFrom(containerArgument.getType())) {
                // Provide a custom legacy value extractor for an array
                containerArgument = (Argument<E>) Argument.of(Object[].class, containerArgument.getAnnotationMetadata());
                valueExtractorDefinitions = List.of(
                    (ValueExtractorDefinition<E>) new ValueExtractorDefinition<>(Object[].class, Object.class, null, false, LEGACY_ARRAY_EXTRACTOR)
                );
            } else {
                if (!explicitUnwrappingConstraints.isEmpty()) {
                    throw new ConstraintDeclarationException("Cannot unwrap the constraint no extractors are present!");
                }
                return false;
            }
        }

        if (!explicitUnwrappingConstraints.isEmpty() && valueExtractorDefinitions.size() > 1) {
            throw new ConstraintDeclarationException("Cannot unwrap the constraint when multiple value extractors are present!");
        }

        long unwrappedCount = valueExtractorDefinitions.stream().filter(ValueExtractorDefinition::unwrapByDefault).count();
        if (unwrappedCount > 1) {
            throw new ConstraintDeclarationException("Multiple unwrap by default value extractors aren't allowed!");
        }

        List<DefaultConstraintDescriptor<Annotation>> containerElementConstraints;

        if (unwrappedCount > 0) {
            if (valueExtractorDefinitions.size() != unwrappedCount) {
                // Only allow one unwrapped by default value extractor
                valueExtractorDefinitions = valueExtractorDefinitions.stream().filter(ValueExtractorDefinition::unwrapByDefault).toList();
            }
            containerElementConstraints = new ArrayList<>(constraints);
            containerElementConstraints.removeAll(skipUnwrappingConstraints);

            validateConstrains(context, leftBean, containerArgument, containerValue, skipUnwrappingConstraints);
        } else {
            containerElementConstraints = explicitUnwrappingConstraints;

            List<DefaultConstraintDescriptor<Annotation>> containerConstraints = new ArrayList<>(constraints);
            containerConstraints.removeAll(explicitUnwrappingConstraints);

            validateConstrains(context, leftBean, containerArgument, containerValue, containerConstraints);
        }

        for (ValueExtractorDefinition<E> valueExtractorDefinition : valueExtractorDefinitions) {

            if (isLegacyValid && valueExtractorDefinition.containerType().equals(Map.class) && valueExtractorDefinition.typeArgumentIndex() == 0) {
                // Legacy Map validation only validates values
                continue;
            }

            Integer typeArgumentIndex = valueExtractorDefinition.typeArgumentIndex();
            Argument<Object> containerValueArgument;
            Argument[] typeParameters = containerArgument.getTypeParameters();
            if (typeArgumentIndex != null && typeArgumentIndex >= 0 && typeParameters.length > 0 && typeArgumentIndex < typeParameters.length) {
                containerValueArgument = typeParameters[typeArgumentIndex];
            } else {
                containerValueArgument = Argument.of(valueExtractorDefinition.valueType());
                typeArgumentIndex = null;
            }
            if (!isValidated(containerValueArgument) && containerElementConstraints.isEmpty() && !isLegacyValid) {
                continue;
            }

            if (containerValue == null) {
                validateConstrains(context, leftBean, containerValueArgument, null, containerElementConstraints);

                continue;
            }

            ValueExtractor<E> valueExtractor = valueExtractorDefinition.valueExtractor();

            try {
                Integer finalTypeArgumentIndex = typeArgumentIndex;
                Argument<E> finalContainerArgument = containerArgument;
                valueExtractor.extractValues(containerValue, new ValueExtractor.ValueReceiver() {

                    @Override
                    public void value(String nodeName, Object val) {
                        ValidationPath.ContainerContext containerContext = ValidationPath.ContainerContext.value(finalContainerArgument.getType(), finalTypeArgumentIndex);
                        validateContainerValue(context, nodeName, containerContext, val);
                    }

                    @Override
                    public void iterableValue(String nodeName, Object iterableValue) {
                        ValidationPath.ContainerContext containerContext = ValidationPath.ContainerContext.iterable(finalContainerArgument.getType(), finalTypeArgumentIndex);
                        validateContainerValue(context, nodeName, containerContext, iterableValue);
                    }

                    @Override
                    public void indexedValue(String nodeName, int index, Object iterableValue) {
                        ValidationPath.ContainerContext containerContext = ValidationPath.ContainerContext.indexed(finalContainerArgument.getType(), index, finalTypeArgumentIndex);
                        validateContainerValue(context, nodeName, containerContext, iterableValue);

                    }

                    @Override
                    public void keyedValue(String nodeName, Object key, Object val) {
                        ValidationPath.ContainerContext containerContext = ValidationPath.ContainerContext.keyed(finalContainerArgument.getType(), key, finalTypeArgumentIndex);
                        validateContainerValue(context, nodeName, containerContext, val);
                    }

                    private void validateContainerValue(Object value) {
                        validateConstrains(context, leftBean, containerValueArgument, value, containerElementConstraints);

                        visitElement(context,
                            leftBean,
                            containerValueArgument,
                            containerValueArgument.getAnnotationMetadata(),
                            value,
                            canCascade,
                            containerValueArgument.getAnnotationMetadata().hasStereotype(Valid.class) || isLegacyValid,
                            true);
                    }

                    private <RX, EX> void validateContainerValue(DefaultConstraintValidatorContext<RX> context,
                                                                 String name,
                                                                 ValidationPath.ContainerContext containerContext,
                                                                 EX value) {
                        if (name != null && !isLegacyValid) {
                            try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addContainerElementNode(name, containerContext)) {
                                validateContainerValue(value);
                            }
                        } else {
                            try (ValidationPath.ContextualPath ignored = context.getCurrentPath().withContainerContext(containerContext)) {
                                validateContainerValue(value);
                            }
                        }
                    }

                });
            } catch (ValidationException e) {
                throw e;
            } catch (Exception e) {
                throw new ValidationException("Exception extracting values using: " + valueExtractor, e);
            }
        }

        return true;
    }

    private <E> boolean isValidated(Argument<E> containerArgument) {
        return containerArgument.getAnnotationMetadata().hasAnnotation(ValidatedElement.class);
    }

    private <R, E> void propagateValidation(DefaultConstraintValidatorContext<R> context,
                                            Object leftBean,
                                            Argument<E> elementType,
                                            E elementValue,
                                            boolean needsCanCascadeCheck) {

        final BeanIntrospection<E> beanIntrospection = getBeanIntrospection(elementValue, elementType.getType());
        if (beanIntrospection == null) {
            // Error if not introspected
            ConstraintDescriptor<Annotation> constraintDescriptor = notIntrospectedConstraint(elementType);
            DefaultConstraintViolation<R> violation = createConstraintViolation(context, leftBean, elementValue, constraintDescriptor);
            context.addViolation(violation);
            return;
        }
        if (!needsCanCascadeCheck || canCascade(context, leftBean)) {
            try (ValidationPath.ContextualPath ignore = context.getCurrentPath().cascaded()) {
                doValidate(context, beanIntrospection, elementValue);
            }
        }
    }

    private <R, E> void validateConstrains(DefaultConstraintValidatorContext<R> context,
                                           @Nullable Object leftBean,
                                           @NonNull Argument<E> elementArgument,
                                           @Nullable E elementValue) {
        AnnotationMetadata annotationMetadata = elementArgument.getAnnotationMetadata();
        List<DefaultConstraintDescriptor<Annotation>> constraints = getConstraints(context, annotationMetadata);
        validateConstrains(context, leftBean, elementArgument, elementValue, constraints);
    }

    private <R, E> void validateConstrains(DefaultConstraintValidatorContext<R> context,
                                           @Nullable Object leftBean,
                                           Argument<E> elementArgument,
                                           @NonNull E elementValue,
                                           @NonNull List<DefaultConstraintDescriptor<Annotation>> constraints) {
        if (constraints.isEmpty()) {
            return;
        }
        ConstraintTarget constraintTarget = context.getCurrentPath().getConstraintTarget();
        for (DefaultConstraintDescriptor<Annotation> constraint : constraints) {
            context.constraint = constraint;
            if (constraint.getValidationAppliesTo() != ConstraintTarget.IMPLICIT && constraint.getValidationAppliesTo() != constraintTarget) {
                continue;
            }
            Class<Annotation> constraintType = constraint.getType();
            List<Class<? extends jakarta.validation.ConstraintValidator<Annotation, ?>>> validatorClasses = constraint.getConstraintValidatorClasses();
            ConstraintValidator<Annotation, E> validator = null;
            if (!validatorClasses.isEmpty()) {
                for (Class<?> validatedBy : validatorClasses) {
                    Class<jakarta.validation.ConstraintValidator<Annotation, E>> validatedByConstraint = (Class<jakarta.validation.ConstraintValidator<Annotation, E>>) validatedBy;
                    jakarta.validation.ConstraintValidator<Annotation, E> constraintValidator = constraintValidatorFactory.getInstance(
                        validatedByConstraint,
                        elementArgument.getType(),
                        constraintTarget
                    );
                    if (constraintValidator != null) {
                        if (constraintValidator instanceof ConstraintValidator<Annotation, E> cv) {
                            validator = cv;
                        } else {
                            validator = new ConstraintValidator<>() {

                                @Override
                                public void initialize(Annotation constraintAnnotation) {
                                    constraintValidator.initialize(constraintAnnotation);
                                }

                                @Override
                                public boolean isValid(E value, AnnotationValue<Annotation> annotationMetadata, ConstraintValidatorContext context) {
                                    return constraintValidator.isValid(value, context);
                                }
                            };
                            AnnotationValue<?> annotationValue = constraint.getAnnotationValue();
                            MutableAnnotationMetadata mutableAnnotationMetadata = new MutableAnnotationMetadata();
                            mutableAnnotationMetadata.addAnnotation(annotationValue.getAnnotationName(), annotationValue.getValues());

                            try {
                                validator.initialize(mutableAnnotationMetadata.synthesize(Constraint.class));
                            } catch (ValidationException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new ValidationException("Cannot call 'initialize' on: " + validatedBy, e);
                            }
                        }
                    }
                }
                if (validator == null) {
                    continue;
                }
            } else {
                if (constraintTarget == ConstraintTarget.PARAMETERS) {
                    continue;
                }
                validator = constraintValidatorRegistry.findConstraintValidator(constraintType, elementArgument.getType()).orElse(null);
            }
            if (validator == null || validator == ConstraintValidator.VALID) {
                throw new UnexpectedTypeException("Cannot find a constraint validator for constraint: " + constraintType.getName() + " and type: " + elementArgument.getType());
            }
            try {
                if (validator.isValid(elementValue, constraint.getAnnotationValue(), context)) {
                    continue;
                }
            } catch (ValidationException e) {
                throw e;
            } catch (Exception e) {
                throw new ValidationException("Cannot call 'isValid' on: " + validator.getClass().getName(), e);
            }

            if (!context.disableDefaultConstraintViolation) {
                DefaultConstraintViolation<R> constraintViolation = createConstraintViolation(context, leftBean, elementValue, constraint);
                context.addViolation(constraintViolation);
            } else if (context.getOverallViolations().isEmpty()) {
                throw new ValidationException("Default violation is disabled and no violations were added");
            }
            context.messageTemplate(null);
            context.constraint = null;
            context.disableDefaultConstraintViolation = false;
        }
    }

    private <R> DefaultConstraintViolation<R> createConstraintViolation(DefaultConstraintValidatorContext<R> context,
                                                                        Object leftBean,
                                                                        Object elementValue,
                                                                        ConstraintDescriptor<Annotation> constraint) {
        final String messageTemplate = buildMessageTemplate(context, constraint);
        final String message = messageInterpolator.interpolate(messageTemplate, new MessageInterpolator.Context() {
            @Override
            public ConstraintDescriptor<?> getConstraintDescriptor() {
                return constraint;
            }

            @Override
            public Object getValidatedValue() {
                return elementValue;
            }

            @Override
            public <T> T unwrap(Class<T> type) {
                throw new ValidationException("Not supported!");
            }
        });

        return new DefaultConstraintViolation<>(
            context.getRootBean(),
            context.getRootClass(),
            leftBean,
            elementValue,
            message,
            messageTemplate,
            new ValidationPath(context.getCurrentPath()),
            constraint,
            context.getExecutableParameterValues(),
            context.getExecutableReturnValue()
        );
    }

    private <R> boolean isConstraintIncluded(DefaultConstraintValidatorContext<R> context,
                                             DefaultConstraintDescriptor<?> constraint) {
        return context.containsGroup(constraint.getGroups());
    }

    private <R> List<DefaultConstraintDescriptor<Annotation>> getConstraints(DefaultConstraintValidatorContext<R> context,
                                                                             AnnotationMetadata annotationMetadata) {
        return annotationMetadata.getAnnotationTypesByStereotype(Constraint.class)
            .stream().
            flatMap(constraintType -> {
                List<? extends AnnotationValue<? extends Annotation>> annotationValuesByType = annotationMetadata.getAnnotationValuesByType(constraintType);
                if (annotationValuesByType.isEmpty()) {
                    annotationValuesByType = annotationMetadata.getDeclaredAnnotationValuesByType(constraintType);
                }
                return annotationValuesByType.stream()
                    .map(annotationValue -> new DefaultConstraintDescriptor<>(
                        (Class<Annotation>) constraintType,
                        (AnnotationValue<Annotation>) annotationValue,
                        annotationMetadata
                    ))
                    .filter(annotationValue -> isConstraintIncluded(context, annotationValue));
            })
            .toList();
    }

    private <R> String buildMessageTemplate(DefaultConstraintValidatorContext<R> context,
                                            ConstraintDescriptor<Annotation> constraint) {
        String messageTemplate = context.getMessageTemplate().orElse(null);
        if (messageTemplate != null) {
            return messageTemplate;
        }
        return constraint.getMessageTemplate();
    }

    private <T> void failOnError(@NonNull BeanResolutionContext resolutionContext,
                                 Set<ConstraintViolation<T>> errors,
                                 Class<?> beanType) {
        if (!errors.isEmpty()) {
            StringBuilder builder = new StringBuilder()
                .append("Validation failed for bean definition [")
                .append(beanType.getName())
                .append("]\nList of constraint violations:[\n");
            for (ConstraintViolation<?> violation : errors) {
                builder.append('\t').append(violation.getPropertyPath()).append(" - ").append(violation.getMessage()).append('\n');
            }
            builder.append(']');
            throw new BeanInstantiationException(resolutionContext, builder.toString());
        }
    }

    /**
     * Throws a {@link IllegalArgumentException} if the value is null.
     * @param name check name
     * @param value value being checked
     * @return the value
     * @param <T> value Type
     * @deprecated It will be private in a future version.
     */
    @Deprecated(since = "4.3.0")
    public static <T> T requireNonNull(String name, T value) {
        if (value == null) {
            throw new IllegalArgumentException("Argument [" + name + "] cannot be null");
        }
        return value;
    }

    /**
     * Throws a {@link IllegalArgumentException} if the value null or an empty string.
     * @param name check name
     * @param value value being checked
     * @return the value
     * @deprecated It will be private in a future version.
     */
    @Deprecated(since = "4.3.0")
    public static String requireNonEmpty(String name, String value) {
        if (StringUtils.isEmpty(value)) {
            throw new IllegalArgumentException("Argument [" + name + "] cannot be empty");
        }
        return value;
    }

    private static ConstraintDescriptor<Annotation> notIntrospectedConstraint(Argument<?> notIntrospectedArgument) {
        return new ConstraintDescriptor<>() {

            @Override
            public Annotation getAnnotation() {
                throw new IllegalStateException("Not supported!");
            }

            @Override
            public String getMessageTemplate() {
                return "{" + Introspected.class.getName() + ".message}";
            }

            @Override
            public Set<Class<?>> getGroups() {
                return Set.of();
            }

            @Override
            public Set<Class<? extends Payload>> getPayload() {
                return Set.of();
            }

            @Override
            public ConstraintTarget getValidationAppliesTo() {
                return ConstraintTarget.IMPLICIT;
            }

            @Override
            public List<Class<? extends jakarta.validation.ConstraintValidator<Annotation, ?>>> getConstraintValidatorClasses() {
                return List.of();
            }

            @Override
            public Map<String, Object> getAttributes() {
                return Collections.singletonMap("type", notIntrospectedArgument.getType().getName());
            }

            @Override
            public Set<ConstraintDescriptor<?>> getComposingConstraints() {
                return Set.of();
            }

            @Override
            public boolean isReportAsSingleViolation() {
                return false;
            }

            @Override
            public ValidateUnwrappedValue getValueUnwrapping() {
                return ValidateUnwrappedValue.DEFAULT;
            }

            @Override
            public <U> U unwrap(Class<U> type) {
                throw new ValidationException("Not supported");
            }
        };
    }

}
