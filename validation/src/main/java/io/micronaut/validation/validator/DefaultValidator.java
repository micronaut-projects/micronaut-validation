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
import io.micronaut.context.exceptions.BeanInstantiationException;
import io.micronaut.core.annotation.AnnotatedElement;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import io.micronaut.validation.validator.constraints.ConstraintValidatorTargetResolver;
import io.micronaut.core.beans.BeanConstructor;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.beans.BeanPropertyMember;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ArgumentValue;
import io.micronaut.core.type.MutableArgumentValue;
import io.micronaut.core.type.ReturnType;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.CopyOnWriteMap;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.InjectionPoint;
import io.micronaut.inject.MethodReference;
import io.micronaut.inject.ProxyBeanDefinition;
import io.micronaut.inject.annotation.AnnotatedElementValidator;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.inject.validation.BeanDefinitionValidator;
import io.micronaut.reflection.MethodHierarchy;
import io.micronaut.validation.annotation.ValidatedElement;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import io.micronaut.validation.validator.constraints.ConstraintValidatorRegistry;
import io.micronaut.validation.validator.constraints.InternalConstraintValidatorFactory;
import io.micronaut.validation.validator.extractors.ValueExtractorDefinition;
import io.micronaut.validation.validator.extractors.ValueExtractorRegistry;
import io.micronaut.validation.validator.messages.DefaultMessageInterpolatorContext;
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;
import io.micronaut.reflection.ReflectionExecutables;
import io.micronaut.reflection.ReflectiveIntrospection;
import jakarta.inject.Singleton;
import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.TraversableResolver;
import jakarta.validation.UnexpectedTypeException;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ValidateUnwrappedValue;
import jakarta.validation.valueextraction.ValueExtractor;
import jakarta.validation.groups.ConvertGroup;
import org.reactivestreams.Publisher;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.micronaut.validation.ConstraintViolationExceptionUtil.createConstraintViolationException;

/**
 * Default implementation of the {@link Validator} interface.
 *
 * @author graemerocher
 * @author Andriy Dmytruk
 * @since 1.2
 */
@Singleton
@Primary
public class DefaultValidator implements
    Validator, ExecutableMethodValidator, ReactiveValidator, AnnotatedElementValidator, BeanDefinitionValidator {

    private static final ValueExtractor<Object[]> LEGACY_ARRAY_EXTRACTOR = (originalValue, receiver) -> {
        int i = 0;
        for (Object item : originalValue) {
            receiver.indexedValue("<array element>", i++, item);
        }
    };

    final MessageInterpolator messageInterpolator;
    final ConcurrentMap<BeanIntrospection<?>, List<DefaultConstraintValidatorContext.ValidationGroup>> findGroupSequencesCache = new CopyOnWriteMap<>(16 * 1024);

    private final ConstraintValidatorRegistry constraintValidatorRegistry;
    private final ClockProvider clockProvider;
    private final ValueExtractorRegistry valueExtractorRegistry;
    private final TraversableResolver traversableResolver;
    private final ExecutionHandleLocator executionHandleLocator;
    private final ConversionService conversionService;
    private final ValidatorDeclarations declarations;
    private final BeanIntrospector beanIntrospector;
    private final List<ValidationMetadataProvider> metadataProviders;
    private final InternalConstraintValidatorFactory constraintValidatorFactory;
    private final ParameterNameProvider parameterNameProvider;
    private final boolean isPrependPropertyPath;

    // The advantage of CopyOnWriteMap over ConcurrentHashMap is that here we can define a maximum
    // size after which entries are evicted. This can save us from a memory leak if we cache more
    // than we should. We still set it comfortably high to avoid unnecessary evictions.
    private final ConcurrentMap<AnnotationMetadata, List<DefaultConstraintDescriptor<Annotation>>> constraintCache =
        new CopyOnWriteMap<>(65536);

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
        this.metadataProviders = configuration.getMetadataProviders();
        this.constraintValidatorFactory = internalConstraintValidatorFactory(configuration);
        this.parameterNameProvider = configuration.getParameterNameProvider();
        this.isPrependPropertyPath = configuration.isPrependPropertyPath();
        this.declarations = new ValidatorDeclarations(beanIntrospector, configuration.isStrictConstraintDefinitions(), metadataProviders);
    }

    private static InternalConstraintValidatorFactory internalConstraintValidatorFactory(ValidatorConfiguration configuration) {
        if (configuration instanceof DefaultValidatorConfiguration defaultConfiguration) {
            return defaultConfiguration.getInternalConstraintValidatorFactory();
        }
        ConstraintValidatorFactory factory = configuration.getConstraintValidatorFactory();
        return DefaultValidatorConfiguration.toInternalConstraintValidatorFactory(factory);
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
                visitProperty(constraintValidationContext, introspection, object, property.get(), false);
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
                    AnnotationMetadata annotationMetadata = propertyAnnotationMetadata(beanType, beanProperty);

                    Argument<Object> propertyArgument = (Argument<Object>) declarations.configuredPropertyArgument(beanType, propertyName, argumentWithMetadata(beanProperty.asArgument(), annotationMetadata));
                    visitElement(constraintContext, null, propertyArgument, annotationMetadata, value, false);

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
        if (!ConstraintContainers.hasConstraints(element.getAnnotationMetadata(), currentClassLoader())) {
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
            throw createConstraintViolationException(isPrependPropertyPath, constraintViolations);
        }

        final T instance = introspection.instantiate(arguments);
        final Set<ConstraintViolation<T>> errors = validate(introspection, instance);
        if (errors.isEmpty()) {
            return instance;
        }
        throw createConstraintViolationException(isPrependPropertyPath, errors);
    }

    @Override
    public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
        if (clazz == null) {
            throw new IllegalArgumentException();
        }
        Optional<BeanDescriptor> metadataDescriptor = metadataProviders.stream()
            .flatMap(provider -> provider.getConstraintsForClass(clazz).stream())
            .findFirst();
        return beanIntrospector.findIntrospection(clazz)
            .map(introspection -> (BeanDescriptor) new IntrospectedBeanDescriptor(
                introspection,
                beanAnnotationMetadata(introspection),
                propertyAnnotationMetadata(introspection),
                metadataProviders,
                declarations
            ))
            .orElseGet(() -> metadataDescriptor.orElseGet(() -> new EmptyDescriptor(clazz)));
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
        final MethodHierarchy hierarchy = declarations.resolveHierarchy(method);
        ExecutableHierarchy.checkParameterDeclarations(hierarchy);
        final ValidatorDeclarations.ConfiguredExecutable configured = declarations.configuredExecutable(method, hierarchy);
        final Argument<?>[] arguments = configured.arguments();
        final int argLen = arguments.length;
        if (argLen != parameterValues.length) {
            throw new IllegalArgumentException("The method parameter array must have exactly " + argLen + " elements.");
        }

        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(this, null, object, validationContext);
        try (DefaultConstraintValidatorContext.ValidationCloseable ignored1 = context.withExecutableParameterValues(parameterValues)) {
            try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addMethodNode(method)) {
                AnnotationMetadata methodAnnotationMetadata = configured.annotationMetadata();
                validateParametersInternal(context, object, methodAnnotationMetadata, parameterValues, arguments, argLen, getParameterNames(method));
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

        final MethodHierarchy hierarchy = declarations.resolveHierarchy(method);
        ExecutableHierarchy.checkParameterDeclarations(hierarchy);
        final ValidatorDeclarations.ConfiguredExecutable configured = declarations.configuredExecutable(method, hierarchy);
        final Argument<?>[] arguments = configured.arguments();
        final int argLen = arguments.length;
        if (argLen != argumentValues.size()) {
            throw new IllegalArgumentException("The method parameter array must have exactly " + argLen + " elements.");
        }

        Object[] parameters = argumentValues.stream().map(ArgumentValue::getValue).toArray();

        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(this, null, object, BeanValidationContext.fromGroups(groups));
        try (DefaultConstraintValidatorContext.ValidationCloseable ignored1 = context.withExecutableParameterValues(parameters)) {
            try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addMethodNode(method)) {
                AnnotationMetadata methodAnnotationMetadata = configured.annotationMetadata();
                validateParametersInternal(context, object, methodAnnotationMetadata, parameters, arguments, argLen, getParameterNames(method));
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

        return validateParameters(object, ReflectionExecutables.executableMethod(executionHandleLocator, beanIntrospector, method), parameterValues, groups);
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

        return validateReturnValue(object, ReflectionExecutables.executableMethod(executionHandleLocator, beanIntrospector, method), returnValue, groups);
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
        final MethodHierarchy hierarchy = declarations.resolveHierarchy(executableMethod);
        ExecutableHierarchy.checkReturnValueDeclarations(hierarchy);
        final Argument<Object> returnArgument = (Argument<Object>) declarations.configuredExecutable(executableMethod, hierarchy).returnArgument();
        final DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(this, null, bean, validationContext);

        try (DefaultConstraintValidatorContext.ValidationCloseable ignored1 = context.withExecutableReturnValue(returnValue)) {
            try (ValidationPath.ContextualPath ignored2 = context.getCurrentPath().addMethodNode(executableMethod)) {
                try (ValidationPath.ContextualPath ignored3 = context.getCurrentPath().addReturnValueNode()) {
                    List<DefaultConstraintValidatorContext.ValidationGroup> groupSequences = context.findGroupSequences(bean);

                    boolean canCascade = true;
                    for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : groupSequences) {
                        try (DefaultConstraintValidatorContext.GroupsValidation validation = context.withGroupSequence(groupSequence)) {
                            visitElement(context, bean, returnArgument, returnArgument.getAnnotationMetadata(), returnValue, canCascade, false, true);

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
        final BeanIntrospection<? extends T> introspection = beanIntrospector.findIntrospection(declaringClass).orElse(null);
        final BeanConstructor<? extends T> beanConstructor = ReflectionExecutables.beanConstructor((BeanIntrospection) introspection, (Constructor) constructor);
        return validateConstructorParameters(
            declaringClass,
            introspection,
            beanConstructor.getAnnotationMetadata(),
            beanConstructor.getArguments(),
            parameterValues,
            BeanValidationContext.fromGroups(groups),
            getParameterNames(constructor)
        );
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
        return validateConstructorParameters(beanType, null, AnnotationMetadata.EMPTY_METADATA, constructorArguments, parameterValues, validationContext, null);
    }

    /**
     * Validates the parameters of a constructor: the constraints of each parameter, its cascade, and the
     * cross-parameter constraints declared on the constructor itself. The root bean of a violation is
     * {@code null}, the constructor having not run; the root bean class is the declaring type.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> Set<ConstraintViolation<T>> validateConstructorParameters(Class<? extends T> beanType,
                                                                          @Nullable BeanIntrospection<? extends T> introspection,
                                                                          @NonNull AnnotationMetadata constructorMetadata,
                                                                          @NonNull Argument<?>[] constructorArguments,
                                                                          @NonNull Object[] parameterValues,
                                                                          BeanValidationContext validationContext,
                                                                          @Nullable List<String> parameterNames) {
        parameterValues = parameterValues != null ? parameterValues : ArrayUtils.EMPTY_OBJECT_ARRAY;
        final int argLength = constructorArguments.length;
        if (parameterValues.length != argLength) {
            throw new IllegalArgumentException("Expected exactly [" + argLength + "] constructor arguments");
        }
        for (Argument<?> constructorArgument : constructorArguments) {
            ExecutableHierarchy.checkGroupConversions(constructorArgument);
        }
        DefaultConstraintValidatorContext<T> context = introspection == null
            ? (DefaultConstraintValidatorContext<T>) new DefaultConstraintValidatorContext<>(this, null, beanType, validationContext)
            : (DefaultConstraintValidatorContext<T>) new DefaultConstraintValidatorContext(this, introspection, null, validationContext);
        ValidatorDeclarations.ConfiguredExecutable configured = declarations.configuredConstructor(beanType, constructorMetadata, constructorArguments);
        try (DefaultConstraintValidatorContext.ValidationCloseable ignored1 = context.withExecutableParameterValues(parameterValues)) {
            try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addConstructorNode(beanType.getSimpleName(), constructorArguments)) {
                validateParametersInternal(context, null, configured.annotationMetadata(), parameterValues, configured.arguments(), argLength, parameterNames);
            }
        }
        return Collections.unmodifiableSet(context.getOverallViolations());
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorReturnValue(@NonNull Constructor<? extends T> constructor,
                                                                          @NonNull T createdObject,
                                                                          @NonNull Class<?>... groups) {
        requireNonNull("constructor", constructor);
        requireNonNull("createdObject", createdObject);
        requireNonNull("groups", groups);
        final Class<? extends T> declaringClass = constructor.getDeclaringClass();
        final BeanIntrospection<? extends T> introspection = beanIntrospector.findIntrospection(declaringClass).orElse(null);
        final BeanConstructor<? extends T> beanConstructor = ReflectionExecutables.beanConstructor((BeanIntrospection) introspection, (Constructor) constructor);
        // the constraints of a constructor apply to the object it creates: the root bean is null, like for its parameters
        final DefaultConstraintValidatorContext<T> context = introspection == null
            ? (DefaultConstraintValidatorContext<T>) new DefaultConstraintValidatorContext<>(this, null, declaringClass, BeanValidationContext.fromGroups(groups))
            : (DefaultConstraintValidatorContext<T>) new DefaultConstraintValidatorContext(this, introspection, null, BeanValidationContext.fromGroups(groups));
        final ValidatorDeclarations.ConfiguredExecutable configured = declarations.configuredConstructor(declaringClass, beanConstructor.getAnnotationMetadata(), beanConstructor.getArguments());
        final AnnotationMetadata constructorMetadata = configured.annotationMetadata();
        ExecutableHierarchy.checkGroupConversions(constructorMetadata, constructorMetadata.hasStereotype(Valid.class));
        final Argument<T> returnArgument = (Argument<T>) configured.returnArgument();
        try (DefaultConstraintValidatorContext.ValidationCloseable ignored1 = context.withExecutableReturnValue(createdObject)) {
            try (ValidationPath.ContextualPath ignored2 = context.getCurrentPath().addConstructorNode(declaringClass.getSimpleName(), beanConstructor.getArguments())) {
                try (ValidationPath.ContextualPath ignored3 = context.getCurrentPath().addReturnValueNode()) {
                    boolean canCascade = true;
                    for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : context.findGroupSequences(createdObject)) {
                        try (DefaultConstraintValidatorContext.GroupsValidation validation = context.withGroupSequence(groupSequence)) {
                            visitElement(context, createdObject, returnArgument, constructorMetadata, createdObject, canCascade, false, true);
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
    public <T> Publisher<T> validatePublisher(@NonNull ReturnType<?> returnType,
                                              @NonNull Publisher<T> publisher,
                                              @NonNull Class<?>... groups) {
        requireNonNull("publisher", publisher);
        requireNonNull("returnType", returnType);
        requireNonNull("groups", groups);
        return ReactiveValidation.validatePublisher(this, conversionService, returnType, publisher, groups);
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
                return ReactiveValidation.instrumentCompletionStage(this, context, completionStage, argument, true);
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
        final boolean hasConstraint = ConstraintContainers.hasConstraints(annotationMetadata, currentClassLoader());

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
                        final boolean hasConstraint = ConstraintContainers.hasConstraints(executableMethod.getAnnotationMetadata(), currentClassLoader());
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

    private <T> void validateParametersInternal(@NonNull DefaultConstraintValidatorContext<T> context,
                                                @Nullable T bean,
                                                @NonNull AnnotationMetadata methodAnnotationMetadata,
                                                @NonNull Object[] parameters,
                                                @NonNull Argument<?>[] arguments,
                                                int argLen,
                                                @Nullable List<String> parameterNames) {

        List<DefaultConstraintValidatorContext.ValidationGroup> groupSequences = context.findGroupSequences(bean);
        boolean canCascade = true;
        for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : groupSequences) {
            try (DefaultConstraintValidatorContext.GroupsValidation validation = context.withGroupSequence(groupSequence)) {

                if (ConstraintContainers.hasConstraints(methodAnnotationMetadata, currentClassLoader())) {
                    try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addCrossParameterNode()) {
                        validateConstrains(context, bean, Argument.of(Object[].class, methodAnnotationMetadata), parameters, true);
                    }
                }

                for (int parameterIndex = 0; parameterIndex < argLen; parameterIndex++) {
                    Argument<Object> argument = (Argument<Object>) arguments[parameterIndex];
                    if (!isValidated(argument) && !hasValidatedTypeArgument(argument)) {
                        continue;
                    }
                    try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addParameterNode(parameterName(argument, parameterNames, parameterIndex), parameterIndex)) {
                        try (DefaultConstraintValidatorContext.ValidationCloseable ignore = context.convertGroups(argument.getAnnotationMetadata())) {

                            final Class<Object> parameterType = argument.getType();

                            Object parameterValue = parameters[parameterIndex];

                            final boolean hasValue = parameterValue != null;

                            final boolean isPublisher = hasValue && Publishers.isConvertibleToPublisher(parameterType);
                            if (isPublisher) {
                                ReactiveValidation.instrumentPublisherArgumentWithValidation(this, context, parameters, parameterIndex, argument, parameterValue, canCascade);
                                continue;
                            }

                            final boolean isCompletionStage = hasValue && CompletionStage.class.isAssignableFrom(parameterType);
                            if (isCompletionStage) {
                                ReactiveValidation.instrumentCompletionStageArgumentWithValidation(this, context, parameters, parameterIndex, argument, parameterValue, canCascade);
                                continue;
                            }

                            visitElement(context,
                                bean,
                                argument,
                                parameterValue,
                                canCascade,
                                false,
                                true
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

    @Nullable
    private List<String> getParameterNames(MethodReference<?, ?> method) {
        if (parameterNameProvider instanceof DefaultParameterNameProvider) {
            return null;
        }
        return parameterNameProvider.getParameterNames(method.getTargetMethod());
    }

    final String parameterName(MethodReference<?, ?> method, int index) {
        return parameterName(method.getArguments()[index], getParameterNames(method), index);
    }

    @Nullable
    private List<String> getParameterNames(Constructor<?> constructor) {
        if (parameterNameProvider instanceof DefaultParameterNameProvider) {
            return null;
        }
        return parameterNameProvider.getParameterNames(constructor);
    }

    private static String parameterName(Argument<?> argument, @Nullable List<String> parameterNames, int index) {
        if (parameterNames != null && parameterNames.size() > index) {
            return parameterNames.get(index);
        }
        return argument.getName();
    }

    final AnnotationMetadata beanAnnotationMetadata(BeanIntrospection<?> introspection) {
        Class<?> beanType = introspection.getBeanType();
        return additionalAnnotationMetadata(
            introspection.getAnnotationMetadata(),
            provider -> provider.getBeanAnnotationMetadata(beanType),
            provider -> provider.isBeanAnnotationMetadataIgnored(beanType)
        );
    }

    private Map<String, AnnotationMetadata> propertyAnnotationMetadata(BeanIntrospection<?> introspection) {
        Map<String, AnnotationMetadata> metadata = new LinkedHashMap<>();
        for (BeanProperty<?, ?> property : introspection.getBeanProperties()) {
            metadata.put(property.getName(), propertyAnnotationMetadata(introspection.getBeanType(), property));
        }
        return metadata;
    }

    private AnnotationMetadata propertyAnnotationMetadata(Class<?> beanType, BeanProperty<?, ?> property) {
        String propertyName = property.getName();
        return additionalAnnotationMetadata(
            property,
            provider -> provider.getPropertyAnnotationMetadata(beanType, propertyName),
            provider -> provider.isPropertyAnnotationMetadataIgnored(beanType, propertyName)
        );
    }

    private AnnotationMetadata additionalAnnotationMetadata(AnnotationMetadata original,
                                                           Function<ValidationMetadataProvider, AnnotationMetadata> metadataResolver,
                                                           Predicate<ValidationMetadataProvider> ignoreResolver) {
        List<AnnotationMetadata> metadata = new ArrayList<>();
        boolean ignoreOriginal = false;
        for (ValidationMetadataProvider provider : metadataProviders) {
            AnnotationMetadata additionalMetadata = metadataResolver.apply(provider);
            if (!additionalMetadata.isEmpty()) {
                metadata.add(additionalMetadata);
            }
            if (ignoreResolver.test(provider)) {
                ignoreOriginal = true;
                break;
            }
        }
        if (metadata.isEmpty()) {
            return ignoreOriginal ? AnnotationMetadata.EMPTY_METADATA : original;
        }
        if (!ignoreOriginal) {
            metadata.add(0, original);
        }
        return new AnnotationMetadataHierarchy(metadata.toArray(AnnotationMetadata[]::new));
    }

    private static <T> Argument<T> argumentWithMetadata(Argument<T> argument, AnnotationMetadata annotationMetadata) {
        return Argument.of(argument.getType(), annotationMetadata, argument.getTypeParameters());
    }

    private <R, T> void doValidate(@NonNull DefaultConstraintValidatorContext<R> context,
                                   @NonNull BeanIntrospection<T> introspection,
                                   @NonNull T object) {
        if (context.isValidated(object)) {
            return;
        }
        declarations.checkBeanDeclarations(introspection);
        boolean canCascade = true;
        try (DefaultConstraintValidatorContext.ValidationCloseable ignore = context.validating(object)) {
            Map<Class<?>, List<DefaultConstraintValidatorContext.ValidationGroup>> isolated = context.findIsolatedGroupSequences(introspection);
            for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : context.findGroupSequences(introspection)) {
                try (DefaultConstraintValidatorContext.GroupsValidation validation = context.withGroupSequence(groupSequence)) {

                    List<BeanIntrospection<?>> superIntrospections = declarations.superIntrospections(introspection);
                    AnnotationMetadata annotationMetadata = beanAnnotationMetadata(introspection);
                    if (!declarations.inheritsAllConstraints(annotationMetadata, superIntrospections, currentClassLoader())) {
                        try (ValidationPath.ContextualPath ignore2 = context.getCurrentPath().addBeanNode()) {
                            visitElement(
                                context,
                                object,
                                Argument.of(introspection.getBeanType(), annotationMetadata),
                                annotationMetadata,
                                object,
                                false,
                                false,
                                false
                            );
                        }
                    }
                    // the class-level constraints of the super types, each type its own violations
                    for (BeanIntrospection<?> superIntrospection : superIntrospections) {
                        AnnotationMetadata superMetadata = superIntrospection.getAnnotationMetadata();
                        if (declarations.declaresConstraints(superMetadata, currentClassLoader())) {
                            try (ValidationPath.ContextualPath ignore2 = context.getCurrentPath().addBeanNode()) {
                                visitElement(context, object, Argument.of((Class) superIntrospection.getBeanType(), superMetadata), superMetadata, object, false, false, false);
                            }
                        }
                    }
                    for (BeanProperty<T, Object> property : introspection.getBeanProperties()) {
                        visitProperty(context, introspection, object, property, canCascade, type -> !isolated.containsKey(type));
                    }

                    if (validation.isFailed()) {
                        break;
                    }
                }
                canCascade = false;
            }
            // the constraints a super type redefining the default group sequence declares follow that sequence
            for (Map.Entry<Class<?>, List<DefaultConstraintValidatorContext.ValidationGroup>> entry : isolated.entrySet()) {
                boolean isolatedCanCascade = true;
                for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : entry.getValue()) {
                    try (DefaultConstraintValidatorContext.GroupsValidation validation = context.withGroupSequence(groupSequence)) {
                        for (BeanProperty<T, Object> property : introspection.getBeanProperties()) {
                            visitProperty(context, introspection, object, property, isolatedCanCascade, type -> type == entry.getKey());
                        }
                        if (validation.isFailed()) {
                            break;
                        }
                    }
                    isolatedCanCascade = false;
                }
            }
        }
    }

    private <R, T> void visitProperty(DefaultConstraintValidatorContext<R> context,
                                      BeanIntrospection<T> introspection,
                                      T object,
                                      BeanProperty<T, Object> property,
                                      boolean canCascade) {
        visitProperty(context, introspection, object, property, canCascade, type -> true);
    }

    /**
     * Visits a property, the members declared by the types the filter accepts: a type redefining the default
     * group sequence validates its members in its own passes.
     */
    private <R, T> void visitProperty(DefaultConstraintValidatorContext<R> context,
                                      BeanIntrospection<T> introspection,
                                      T object,
                                      BeanProperty<T, Object> property,
                                      boolean canCascade,
                                      Predicate<Class<?>> declaringTypes) {
        if (property.isWriteOnly()) {
            return;
        }
        String propertyName = property.getName();
        Class<?> beanType = object.getClass();
        if (!hasConfiguredPropertyMetadata(beanType, propertyName) && introspection instanceof ReflectiveIntrospection) {
            // the members declaring constraints are validated one by one, each against the value it holds. A
            // generated introspection reports its members only where the type asked for them, and merges what
            // they declare into the property, so walking them is what a reflective description needs and what a
            // generated one must not have done for it twice
            List<? extends BeanPropertyMember<T, ?>> members = property.getMembers().stream()
                .filter(BeanPropertyMember::isReadable)
                .filter(this::isValidatedMember)
                .toList();
            if (!members.isEmpty()) {
                // the value is cascaded once, by the first member marking it so
                boolean cascadeLeft = canCascade;
                for (BeanPropertyMember<T, ?> member : members) {
                    if (declaringTypes.test(member.getDeclaringType())) {
                        visitPropertyMember(context, object, property, member, cascadeLeft);
                    }
                    if (isCascadedMember(member)) {
                        cascadeLeft = false;
                    }
                }
                return;
            }
        }
        if (!declaringTypes.test(beanType)) {
            return;
        }
        try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addPropertyNode(propertyName)) {
            if (isNotReachable(context, object) ||
                !context.getValidationContext().isPropertyValidated(object, property)) {
                return;
            }
            AnnotationMetadata annotationMetadata = propertyAnnotationMetadata(beanType, property);
            try (DefaultConstraintValidatorContext.ValidationCloseable ignore = context.convertGroups(annotationMetadata)) {
                Object propertyValue;
                try {
                    propertyValue = property.get(object);
                } catch (Exception e) {
                    throw new ValidationException("Failed to get the value of property: " + propertyName, e);
                }
                Argument<Object> propertyArgument = (Argument<Object>) declarations.configuredPropertyArgument(beanType, propertyName, argumentWithMetadata(property.asArgument(), annotationMetadata));
                visitElement(
                    context,
                    object,
                    propertyArgument,
                    annotationMetadata,
                    propertyValue,
                    canCascade,
                    true,
                    false
                );
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <R, T> void visitPropertyMember(DefaultConstraintValidatorContext<R> context,
                                            T object,
                                            BeanProperty<T, Object> property,
                                            BeanPropertyMember<T, ?> member,
                                            boolean canCascade) {
        try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addPropertyNode(property.getName())) {
            Class<?> implicitGroup = member.getDeclaringType().isInterface() ? member.getDeclaringType() : null;
            try (DefaultConstraintValidatorContext.ValidationCloseable ignore = context.withMember(member.getElementType(), implicitGroup)) {
                if (isNotReachable(context, object) ||
                    !context.getValidationContext().isPropertyValidated(object, property)) {
                    return;
                }
                AnnotationMetadata memberMetadata = member.getAnnotationMetadata();
                try (DefaultConstraintValidatorContext.ValidationCloseable ignore2 = context.convertGroups(memberMetadata)) {
                    Object value;
                    try {
                        value = member.read(object);
                    } catch (Exception e) {
                        throw new ValidationException("Failed to get the value of property: " + property.getName(), e);
                    }
                    Argument<Object> argument = Argument.of((Class) member.asArgument().getType(), property.getName(), memberMetadata, member.asArgument().getTypeParameters());
                    visitElement(context, object, argument, memberMetadata, value, canCascade, true, false);
                }
            }
        }
    }

    private boolean isCascadedMember(BeanPropertyMember<?, ?> member) {
        return member.getAnnotationMetadata().hasStereotype(Valid.class) || hasCascadedTypeArgument(member.asArgument());
    }

    /**
     * Whether a member of a property declares something to validate: constraints, a cascade, constrained
     * type arguments or group conversions.
     */
    private boolean isValidatedMember(BeanPropertyMember<?, ?> member) {
        AnnotationMetadata annotationMetadata = member.getAnnotationMetadata();
        return ConstraintContainers.hasConstraints(annotationMetadata, currentClassLoader())
            || annotationMetadata.hasStereotype(Valid.class)
            || hasValidatedTypeArgument(member.asArgument())
            || !annotationMetadata.getAnnotationValuesByType(ConvertGroup.class).isEmpty();
    }

    /**
     * Whether a metadata provider configures or replaces the annotations of a property: the property is then
     * validated as one element, the way the configuration describes it.
     */
    private boolean hasConfiguredPropertyMetadata(Class<?> beanType, String propertyName) {
        for (ValidationMetadataProvider provider : metadataProviders) {
            if (provider.isPropertyAnnotationMetadataIgnored(beanType, propertyName)
                || !provider.getPropertyAnnotationMetadata(beanType, propertyName).isEmpty()
                || provider.getConstraintsForClass(beanType).isPresent()) {
                return true;
            }
        }
        return false;
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
                    context.elementType()
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
                    context.elementType()
            );
        } catch (Exception e) {
            throw new ValidationException("Cannot call 'isCascadable' on traversableResolver: " + traversableResolver, e);
        }
    }

    final ValidatorDeclarations declarations() {
        return declarations;
    }

    final ConversionService conversionService() {
        return conversionService;
    }

    final <T> ConstraintViolationException constraintViolationException(Set<ConstraintViolation<T>> violations) {
        return createConstraintViolationException(isPrependPropertyPath, violations);
    }

    final <R, E> void visitElement(DefaultConstraintValidatorContext<R> context,
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
                                     boolean needsCanCascadeCheck,
                                     boolean cacheConstraints) {
        AnnotationMetadata annotationMetadata = elementArgument.getAnnotationMetadata();
        visitElement(context,
            bean,
            elementArgument,
            annotationMetadata,
            elementValue,
            canCascade,
            needsCanCascadeCheck,
            cacheConstraints
        );
    }

    private <R, E> void visitElement(DefaultConstraintValidatorContext<R> context,
                                     Object bean,
                                     Argument<E> elementArgument,
                                     AnnotationMetadata annotationMetadata,
                                     E elementValue,
                                     boolean canCascade,
                                     boolean needsCanCascadeCheck,
                                     boolean cacheConstraints) {
        visitElement(context,
            bean,
            elementArgument,
            annotationMetadata,
            elementValue,
            canCascade,
            canCascade && annotationMetadata.hasStereotype(Valid.class),
            needsCanCascadeCheck,
            cacheConstraints
        );
    }

    private <R, E> void visitElement(DefaultConstraintValidatorContext<R> context,
                                     Object leftBean,
                                     Argument<E> elementArgument,
                                     AnnotationMetadata annotationMetadata,
                                     E elementValue,
                                     boolean canCascade,
                                     boolean hasValid,
                                     boolean needsCanCascadeCheck,
                                     boolean cacheConstraints) {

        List<DefaultConstraintDescriptor<Annotation>> constraints = getConstraints(context, annotationMetadata, cacheConstraints);

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
        if (!isValidated(containerArgument) && !hasValidatedTypeArgument(containerArgument)) {
            return false;
        }

        boolean isLegacyValid = annotationMetadata.hasAnnotation(Valid.class)
            && (Iterable.class.isAssignableFrom(containerArgument.getType())
            || Map.class.isAssignableFrom(containerArgument.getType())
            || Object[].class.isAssignableFrom(containerArgument.getType())
        );

        boolean anyExplicitUnwrapping = false;
        for (DefaultConstraintDescriptor<Annotation> constraint : constraints) {
            if (constraint.getValueUnwrapping() == ValidateUnwrappedValue.UNWRAP) {
                anyExplicitUnwrapping = true;
                break;
            }
        }

        Class<E> extractorLookupType = containerArgument.getType();
        if (containerValue != null
            && hasCascadedTypeArgument(containerArgument)
            && !hasConstrainedTypeArgument(context, containerArgument)) {
            extractorLookupType = (Class<E>) containerValue.getClass();
        }
        List<ValueExtractorDefinition<E>> valueExtractorDefinitions = valueExtractorRegistry.findValueExtractors(extractorLookupType);
        if (valueExtractorDefinitions.isEmpty()) {
            if (isLegacyValid && Object[].class.isAssignableFrom(containerArgument.getType())) {
                // Provide a custom legacy value extractor for an array
                containerArgument = (Argument<E>) Argument.of(Object[].class, containerArgument.getAnnotationMetadata());
                valueExtractorDefinitions = List.of(
                    (ValueExtractorDefinition<E>) new ValueExtractorDefinition<>(Object[].class, Object.class, null, false, LEGACY_ARRAY_EXTRACTOR)
                );
            } else {
                if (anyExplicitUnwrapping) {
                    throw new ConstraintDeclarationException("Cannot unwrap the constraint no extractors are present!");
                }
                if (hasValidatedTypeArgument(containerArgument)) {
                    throw new ConstraintDeclarationException("Cannot validate container element constraints without a value extractor for " + containerArgument.getType().getName());
                }
                return false;
            }
        }
        validateValueExtractorCoverage(containerArgument, valueExtractorDefinitions);

        if (anyExplicitUnwrapping && valueExtractorDefinitions.size() > 1) {
            throw new ConstraintDeclarationException("Cannot unwrap the constraint when multiple value extractors are present!");
        }

        ValueExtractorDefinition<E> singleUnwrapByDefault = null;
        for (ValueExtractorDefinition<E> definition : valueExtractorDefinitions) {
            if (definition.unwrapByDefault()) {
                if (singleUnwrapByDefault != null) {
                    throw new ConstraintDeclarationException("Multiple unwrap by default value extractors aren't allowed!");
                }
                singleUnwrapByDefault = definition;
            }
        }

        List<DefaultConstraintDescriptor<Annotation>> containerElementConstraints;

        if (singleUnwrapByDefault != null) {
            if (valueExtractorDefinitions.size() != 1) {
                // Only allow one unwrapped by default value extractor
                valueExtractorDefinitions = List.of(singleUnwrapByDefault);
            }
            containerElementConstraints = new ArrayList<>();
            List<DefaultConstraintDescriptor<Annotation>> skipUnwrappingConstraints = new ArrayList<>();
            for (DefaultConstraintDescriptor<Annotation> constraint : constraints) {
                if (constraint.getValueUnwrapping() == ValidateUnwrappedValue.SKIP) {
                    skipUnwrappingConstraints.add(constraint);
                } else {
                    containerElementConstraints.add(constraint);
                }
            }

            validateConstrains(context, leftBean, containerArgument, containerValue, skipUnwrappingConstraints);
        } else {
            containerElementConstraints = new ArrayList<>();

            List<DefaultConstraintDescriptor<Annotation>> containerConstraints = new ArrayList<>();
            for (DefaultConstraintDescriptor<Annotation> constraint : constraints) {
                if (constraint.getValueUnwrapping() == ValidateUnwrappedValue.UNWRAP) {
                    containerElementConstraints.add(constraint);
                } else {
                    containerConstraints.add(constraint);
                }
            }

            validateConstrains(context, leftBean, containerArgument, containerValue, containerConstraints);
        }

        for (ValueExtractorDefinition<E> valueExtractorDefinition : valueExtractorDefinitions) {

            if (isLegacyValid && valueExtractorDefinition.containerType().equals(Map.class) && valueExtractorDefinition.typeArgumentIndex() == 0) {
                // Legacy Map validation only validates values
                continue;
            }

            Integer typeArgumentIndex = valueExtractorDefinition.typeArgumentIndex();
            Integer declaredTypeArgumentIndex = ContainerTypeArguments.resolveExtractedTypeArgumentIndex(
                containerArgument.getType(),
                valueExtractorDefinition.containerType(),
                typeArgumentIndex
            );
            Argument<Object> containerValueArgument;
            boolean unwrapping = false;
            Argument[] typeParameters = containerArgument.getTypeParameters();
            if (declaredTypeArgumentIndex != null && declaredTypeArgumentIndex >= 0 && typeParameters.length > 0 && declaredTypeArgumentIndex < typeParameters.length) {
                containerValueArgument = typeParameters[declaredTypeArgumentIndex];
            } else {
                // a container without type arguments of its own binds the extracted one in a generic super type
                Argument<?> bound = typeArgumentIndex == null ? null
                    : ContainerTypeArguments.resolveBoundTypeArgument(containerArgument.getType(), valueExtractorDefinition.containerType(), typeArgumentIndex);
                unwrapping = typeArgumentIndex == null;
                if (bound != null) {
                    containerValueArgument = (Argument<Object>) bound;
                } else if (typeArgumentIndex == null) {
                    // an unwrapped value keeps the type arguments declared on the container wrapping it
                    containerValueArgument = (Argument<Object>) Argument.of((Class) valueExtractorDefinition.valueType(), containerArgument.getName(), AnnotationMetadata.EMPTY_METADATA, typeParameters);
                } else {
                    containerValueArgument = (Argument<Object>) Argument.of((Class) valueExtractorDefinition.valueType());
                }
                declaredTypeArgumentIndex = null;
            }
            if (!isValidated(containerValueArgument) && !hasValidatedTypeArgument(containerValueArgument)
                && containerElementConstraints.isEmpty() && !isLegacyValid) {
                // nothing to validate at this level nor deeper: a generated metadata marks the levels, a reflective one does not
                continue;
            }

            if (containerValue == null) {
                validateConstrains(context, leftBean, containerValueArgument, null, containerElementConstraints);

                continue;
            }

            ValueExtractor<E> valueExtractor = valueExtractorDefinition.valueExtractor();

            try {
                Integer finalTypeArgumentIndex = declaredTypeArgumentIndex;
                Argument<E> finalContainerArgument = containerArgument;
                boolean finalUnwrapping = unwrapping;
                valueExtractor.extractValues(containerValue, new ValueExtractor.ValueReceiver() {

                    @Override
                    public void value(String nodeName, Object val) {
                        ValidationPath.ContainerContext containerContext = ValidationPath.ContainerContext.value(context.reportedContainerType(finalContainerArgument.getType()), finalTypeArgumentIndex);
                        validateContainerValue(context, nodeName, containerContext, val);
                    }

                    @Override
                    public void iterableValue(String nodeName, Object iterableValue) {
                        ValidationPath.ContainerContext containerContext = ValidationPath.ContainerContext.iterable(context.reportedContainerType(finalContainerArgument.getType()), finalTypeArgumentIndex);
                        validateContainerValue(context, nodeName, containerContext, iterableValue);
                    }

                    @Override
                    public void indexedValue(String nodeName, int index, Object iterableValue) {
                        ValidationPath.ContainerContext containerContext = ValidationPath.ContainerContext.indexed(context.reportedContainerType(finalContainerArgument.getType()), index, finalTypeArgumentIndex);
                        validateContainerValue(context, nodeName, containerContext, iterableValue);

                    }

                    @Override
                    public void keyedValue(String nodeName, Object key, Object val) {
                        ValidationPath.ContainerContext containerContext = ValidationPath.ContainerContext.keyed(context.reportedContainerType(finalContainerArgument.getType()), key, finalTypeArgumentIndex);
                        validateContainerValue(context, nodeName, containerContext, val);
                    }

                    private void validateContainerValue(Object value) {
                        validateConstrains(context, leftBean, containerValueArgument, value, containerElementConstraints);
                        try (DefaultConstraintValidatorContext.ValidationCloseable ignored = finalUnwrapping
                            ? context.withUnwrappedContainer(context.reportedContainerType(finalContainerArgument.getType()))
                            : () -> { }) {
                            visitElement(context,
                                leftBean,
                                containerValueArgument,
                                containerValueArgument.getAnnotationMetadata(),
                                value,
                                canCascade,
                                containerValueArgument.getAnnotationMetadata().hasStereotype(Valid.class) || isLegacyValid,
                                true,
                                false // might be possible to cache, investigate if there's a perf problem here
                            );
                        }
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

    /**
     * Whether an argument is validated: the processor marks one with {@code ValidatedElement} when it
     * carries a constraint or a cascade, and an argument read reflectively carries the constraint or the
     * cascade itself.
     */
    private <E> boolean isValidated(Argument<E> containerArgument) {
        AnnotationMetadata annotationMetadata = containerArgument.getAnnotationMetadata();
        return annotationMetadata.hasAnnotation(ValidatedElement.class)
            || ConstraintContainers.hasConstraints(annotationMetadata, currentClassLoader())
            || annotationMetadata.hasAnnotation(Valid.class);
    }

    private boolean hasValidatedTypeArgument(Argument<?> argument) {
        for (Argument<?> typeParameter : argument.getTypeParameters()) {
            if (isValidated(typeParameter) || hasValidatedTypeArgument(typeParameter)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCascadedTypeArgument(Argument<?> argument) {
        for (Argument<?> typeParameter : argument.getTypeParameters()) {
            AnnotationMetadata annotationMetadata = typeParameter.getAnnotationMetadata();
            if (annotationMetadata.hasAnnotation(Valid.class)
                || annotationMetadata.hasStereotype(Valid.class)
                || hasCascadedTypeArgument(typeParameter)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasConstrainedTypeArgument(DefaultConstraintValidatorContext<?> context, Argument<?> argument) {
        for (Argument<?> typeParameter : argument.getTypeParameters()) {
            if (!getConstraints(context, typeParameter.getAnnotationMetadata(), false).isEmpty()
                || hasConstrainedTypeArgument(context, typeParameter)) {
                return true;
            }
        }
        return false;
    }

    private void validateValueExtractorCoverage(Argument<?> containerArgument,
                                                List<? extends ValueExtractorDefinition<?>> valueExtractorDefinitions) {
        Argument<?>[] typeParameters = containerArgument.getTypeParameters();
        for (int i = 0; i < typeParameters.length; i++) {
            if (isValidated(typeParameters[i]) && !hasValueExtractorForTypeArgument(containerArgument.getType(), valueExtractorDefinitions, i)) {
                throw new ConstraintDeclarationException("Cannot validate container element constraints without a value extractor for type argument " + i + " of " + containerArgument.getType().getName());
            }
        }
    }

    private boolean hasValueExtractorForTypeArgument(Class<?> declaredType,
                                                     List<? extends ValueExtractorDefinition<?>> valueExtractorDefinitions,
                                                     int typeArgumentIndex) {
        for (ValueExtractorDefinition<?> valueExtractorDefinition : valueExtractorDefinitions) {
            Integer declaredTypeArgumentIndex = ContainerTypeArguments.resolveExtractedTypeArgumentIndex(
                declaredType,
                valueExtractorDefinition.containerType(),
                valueExtractorDefinition.typeArgumentIndex()
            );
            if (Objects.equals(declaredTypeArgumentIndex, typeArgumentIndex)) {
                return true;
            }
        }
        return false;
    }

    private <R, E> void propagateValidation(DefaultConstraintValidatorContext<R> context,
                                            Object leftBean,
                                            Argument<E> elementType,
                                            E elementValue,
                                            boolean needsCanCascadeCheck) {

        final BeanIntrospection<E> beanIntrospection = getBeanIntrospection(elementValue, elementType.getType());
        if (beanIntrospection == null) {
            // Error if not introspected
            ConstraintDescriptor<Annotation> constraintDescriptor = notIntrospectedConstraint(elementType, elementValue);
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
                                           @Nullable E elementValue,
                                           boolean cacheConstraints) {
        AnnotationMetadata annotationMetadata = elementArgument.getAnnotationMetadata();
        List<DefaultConstraintDescriptor<Annotation>> constraints = getConstraints(context, annotationMetadata, cacheConstraints);
        validateConstrains(context, leftBean, elementArgument, elementValue, constraints);
    }

    private <R, E> void validateConstrains(DefaultConstraintValidatorContext<R> context,
                                           @Nullable Object leftBean,
                                           Argument<E> elementArgument,
                                           @Nullable E elementValue,
                                           @NonNull List<DefaultConstraintDescriptor<Annotation>> constraints) {
        if (constraints.isEmpty()) {
            return;
        }
        ConstraintTarget constraintTarget = context.getCurrentPath().getConstraintTarget();
        ValidationPath.DefaultMethodNode executableNode = executableNode(context.getCurrentPath());
        for (DefaultConstraintDescriptor<Annotation> constraint : constraints) {
            context.constraint = constraint;
            ConstraintTarget validationAppliesTo = constraint.getValidationAppliesTo();
            if (constraint.hasDefinedConstraintValidatorClasses() || validationAppliesTo != null) {
                boolean onExecutable = executableNode != null
                    && (constraintTarget == ConstraintTarget.PARAMETERS || constraintTarget == ConstraintTarget.RETURN_VALUE);
                ConstraintValidatorTargetResolver.checkTargetDeclaration(
                    constraint.getType(),
                    constraint.getConstraintValidatorClasses(),
                    validationAppliesTo,
                    onExecutable,
                    executableNode != null && executableNode.getMethodReference().getArguments().length > 0,
                    executableNode instanceof ValidationPath.DefaultConstructorNode
                        || (executableNode != null && executableNode.getMethodReference().getReturnType().getType() != void.class)
                );
            }
            if (validationAppliesTo != null && validationAppliesTo != ConstraintTarget.IMPLICIT && validationAppliesTo != constraintTarget) {
                continue;
            }
            Class<Annotation> constraintType = constraint.getType();
            List<Class<? extends jakarta.validation.ConstraintValidator<Annotation, ?>>> validatorClasses = constraint.getConstraintValidatorClasses();
            ConstraintValidator<Annotation, E> validator = null;
            if (constraint.hasDefinedConstraintValidatorClasses()) {
                if ((constraintTarget == ConstraintTarget.PARAMETERS || constraintTarget == ConstraintTarget.RETURN_VALUE)
                    && !ConstraintValidatorTargetResolver.supportsTarget(validatorClasses, constraintTarget)) {
                    // a constraint declared on an executable whose validators do not validate this phase
                    continue;
                }
                Class<?> validatedBy = ConstraintValidatorTargetResolver.resolve(constraintType, validatorClasses, elementArgument.getType(), constraintTarget);
                if (validatedBy != null) {
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
                            try {
                                validator.initialize(constraint.getAnnotation());
                            } catch (ValidationException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new ValidationException("Cannot call 'initialize' on: " + validatedBy, e);
                            }
                        }
                    }
                }
                if (validator == null) {
                    if (!validatorClasses.isEmpty()) {
                        throw new UnexpectedTypeException("Cannot find a constraint validator for constraint: " + constraintType.getName() + " and type: " + elementArgument.getType());
                    }
                    continue;
                }
            } else {
                if (constraintTarget == ConstraintTarget.PARAMETERS) {
                    continue;
                }
                validator = constraintValidatorRegistry.findConstraintValidator(constraintType, elementArgument.getType()).orElse(null);
            }
            if (validator == null || validator == ConstraintValidator.VALID) {
                if (validateComposingConstraints(context, leftBean, elementArgument, elementValue, constraint)) {
                    continue;
                }
                throw new UnexpectedTypeException("Cannot find a constraint validator for constraint: " + constraintType.getName() + " and type: " + elementArgument.getType());
            }
            try {
                if (!validator.isValid(elementValue, constraint.getAnnotationValue(), context)) {
                    if (!context.disableDefaultConstraintViolation) {
                        DefaultConstraintViolation<R> constraintViolation = createConstraintViolation(context, leftBean, elementValue, constraint);
                        context.addViolation(constraintViolation);
                    } else if (context.getOverallViolations().isEmpty()) {
                        throw new ValidationException("Default violation is disabled and no violations were added");
                    }
                }
            } catch (ValidationException e) {
                throw e;
            } catch (Exception e) {
                throw new ValidationException("Cannot call 'isValid' on: " + validator.getClass().getName(), e);
            }

            validateComposingConstraints(context, leftBean, elementArgument, elementValue, constraint);
            context.messageTemplate(null);
            context.constraint = null;
            context.disableDefaultConstraintViolation = false;
        }
    }

    private <R, E> boolean validateComposingConstraints(DefaultConstraintValidatorContext<R> context,
                                                        @Nullable Object leftBean,
                                                        Argument<E> elementArgument,
                                                        @Nullable E elementValue,
                                                        DefaultConstraintDescriptor<Annotation> constraint) {
        if (!constraint.hasComposingConstraints()) {
            return false;
        }
        List<DefaultConstraintDescriptor<Annotation>> composingConstraints = List.copyOf(constraint.getComposingConstraintDescriptors());
        if (constraint.isReportAsSingleViolation()) {
            Set<ConstraintViolation<R>> existingViolations = new LinkedHashSet<>(context.getOverallViolations());
            validateConstrains(context, leftBean, elementArgument, elementValue, composingConstraints);
            if (!existingViolations.containsAll(context.getOverallViolations())) {
                context.getOverallViolations().removeIf(violation -> !existingViolations.contains(violation));
                context.addViolation(createConstraintViolation(context, leftBean, elementValue, constraint));
            }
        } else {
            validateConstrains(context, leftBean, elementArgument, elementValue, composingConstraints);
        }
        return true;
    }

    private <R> DefaultConstraintViolation<R> createConstraintViolation(DefaultConstraintValidatorContext<R> context,
                                                                        @Nullable Object leftBean,
                                                                        @Nullable Object elementValue,
                                                                        ConstraintDescriptor<Annotation> constraint) {
        final String messageTemplate = buildMessageTemplate(context, constraint);
        final String message;
        try {
            message = messageInterpolator.interpolate(messageTemplate, new DefaultMessageInterpolatorContext(context, constraint, elementValue));
        } catch (ValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ValidationException("Exception during message interpolation", e);
        }

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
                                                                             AnnotationMetadata annotationMetadata,
                                                                             boolean cache) {
        if (cache) {
            List<DefaultConstraintDescriptor<Annotation>> cached = constraintCache.computeIfAbsent(annotationMetadata, m -> getConstraints0(null, m));
            if (!cached.isEmpty()) {
                cached = new ArrayList<>(cached);
                cached.removeIf(descriptor -> !isConstraintIncluded(context, descriptor));
            }
            return cached;
        } else {
            return getConstraints0(context, annotationMetadata);
        }
    }

    private <R> List<DefaultConstraintDescriptor<Annotation>> getConstraints0(@Nullable DefaultConstraintValidatorContext<R> context,
                                                                              AnnotationMetadata annotationMetadata) {
        List<DefaultConstraintDescriptor<Annotation>> descriptors = new ArrayList<>();
        Set<String> declaredAnnotationNames = annotationMetadata.getDeclaredAnnotationNames();
        Set<Class<? extends Annotation>> constraintTypes = ConstraintContainers.constraintTypes(annotationMetadata, currentClassLoader());
        boolean hasDeclaredConstraint = constraintTypes.stream().anyMatch(type -> ConstraintAnnotationKey.isDeclaredConstraint(declaredAnnotationNames, type));
        for (Class<? extends Annotation> constraintType : constraintTypes) {
            if (hasDeclaredConstraint && !ConstraintAnnotationKey.isDeclaredConstraint(declaredAnnotationNames, constraintType)) {
                continue;
            }
            declarations.checkConstraintDefinition(constraintType);
            List<? extends AnnotationValue<? extends Annotation>> annotationValuesByType = ConstraintContainers.values(annotationMetadata, constraintType);
            Map<String, AnnotationValue<? extends Annotation>> uniqueAnnotationValues = new LinkedHashMap<>();
            for (AnnotationValue<? extends Annotation> annotationValue : annotationValuesByType) {
                uniqueAnnotationValues.putIfAbsent(ConstraintAnnotationKey.of(constraintType, annotationValue), annotationValue);
            }
            for (AnnotationValue<? extends Annotation> annotationValue : uniqueAnnotationValues.values()) {
                Optional<List<Class<? extends jakarta.validation.ConstraintValidator<Annotation, ?>>>> validatorClasses = constraintValidatorClasses(
                    (Class<Annotation>) constraintType,
                    (AnnotationValue<Annotation>) annotationValue
                );
                DefaultConstraintDescriptor<Annotation> descriptor = validatorClasses
                    .map(classes -> new DefaultConstraintDescriptor<>(
                        (Class<Annotation>) constraintType,
                        (AnnotationValue<Annotation>) annotationValue,
                        annotationMetadata,
                        classes,
                        true
                    ))
                    .orElseGet(() -> new DefaultConstraintDescriptor<>(
                        (Class<Annotation>) constraintType,
                        (AnnotationValue<Annotation>) annotationValue,
                        annotationMetadata
                    ));
                if (context == null || isConstraintIncluded(context, descriptor)) {
                    descriptors.add(descriptor);
                }
            }
        }
        return descriptors;
    }

    private Optional<List<Class<? extends jakarta.validation.ConstraintValidator<Annotation, ?>>>> constraintValidatorClasses(
        Class<Annotation> constraintType,
        AnnotationValue<Annotation> annotationValue) {
        List<Class<? extends jakarta.validation.ConstraintValidator<Annotation, ?>>> validatorClasses =
            (List) List.of(annotationValue.classValues(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY));
        Optional<List<Class<? extends jakarta.validation.ConstraintValidator<Annotation, ?>>>> configuredClasses = Optional.empty();
        for (ValidationMetadataProvider metadataProvider : metadataProviders) {
            Optional<List<Class<? extends jakarta.validation.ConstraintValidator<Annotation, ?>>>> providerClasses =
                metadataProvider.getConstraintValidatorClasses(constraintType, validatorClasses);
            if (providerClasses.isPresent()) {
                configuredClasses = providerClasses;
                validatorClasses = providerClasses.get();
            }
        }
        return configuredClasses;
    }

    private static ClassLoader currentClassLoader() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        return classLoader == null ? DefaultValidator.class.getClassLoader() : classLoader;
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
     */
    private static <T> T requireNonNull(String name, T value) {
        if (value == null) {
            throw new IllegalArgumentException("Argument [" + name + "] cannot be null");
        }
        return value;
    }

    /**
     * @return The node of the executable being validated, {@code null} when a bean is
     */
    private static ValidationPath.@Nullable DefaultMethodNode executableNode(ValidationPath path) {
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i) instanceof ValidationPath.DefaultMethodNode methodNode) {
                return methodNode;
            }
        }
        return null;
    }

    private static String requireNonEmpty(String name, String value) {
        if (StringUtils.isEmpty(value)) {
            throw new IllegalArgumentException("Argument [" + name + "] cannot be empty");
        }
        return value;
    }

    private static <E> ConstraintDescriptor<Annotation> notIntrospectedConstraint(Argument<E> notIntrospectedArgument, E elementValue) {
        return new NotIntrospectedConstraintDescriptor<>(notIntrospectedArgument, elementValue);
    }

}
