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
import io.micronaut.context.MessageSource;
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
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.InjectionPoint;
import io.micronaut.inject.MethodReference;
import io.micronaut.inject.ProxyBeanDefinition;
import io.micronaut.inject.annotation.AnnotatedElementValidator;
import io.micronaut.inject.validation.BeanDefinitionValidator;
import io.micronaut.inject.validation.RequiresValidation;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import io.micronaut.validation.validator.constraints.ConstraintValidatorRegistry;
import io.micronaut.validation.validator.extractors.ValueExtractorRegistry;
import jakarta.inject.Singleton;
import jakarta.validation.ClockProvider;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.GroupSequence;
import jakarta.validation.Path;
import jakarta.validation.TraversableResolver;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ConstructorDescriptor;
import jakarta.validation.metadata.ElementDescriptor;
import jakarta.validation.metadata.MethodDescriptor;
import jakarta.validation.metadata.MethodType;
import jakarta.validation.metadata.PropertyDescriptor;
import jakarta.validation.metadata.Scope;
import jakarta.validation.valueextraction.ValueExtractor;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final Map<Class<?>, List<Class<?>>> GROUP_SEQUENCES = new ConcurrentHashMap<>();
    private static final List<Class<?>> DEFAULT_GROUPS = Collections.singletonList(Default.class);
    private final ConstraintValidatorRegistry constraintValidatorRegistry;
    private final ClockProvider clockProvider;
    private final ValueExtractorRegistry valueExtractorRegistry;
    private final TraversableResolver traversableResolver;
    private final ExecutionHandleLocator executionHandleLocator;
    private final MessageSource messageSource;
    private final ConversionService conversionService;
    private final BeanIntrospector beanIntrospector;

    /**
     * Default constructor.
     *
     * @param configuration The validator configuration
     */
    public DefaultValidator(@NonNull ValidatorConfiguration configuration) {
        ArgumentUtils.requireNonNull("configuration", configuration);
        this.constraintValidatorRegistry = configuration.getConstraintValidatorRegistry();
        this.clockProvider = configuration.getClockProvider();
        this.valueExtractorRegistry = configuration.getValueExtractorRegistry();
        this.traversableResolver = configuration.getTraversableResolver();
        this.executionHandleLocator = configuration.getExecutionHandleLocator();
        this.messageSource = configuration.getMessageSource();
        this.conversionService = configuration.getConversionService();
        this.beanIntrospector = configuration.getBeanIntrospector();
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validate(@NonNull T object, @Nullable Class<?>... groups) {
        ArgumentUtils.requireNonNull("object", object);
        final BeanIntrospection<T> introspection = getBeanIntrospection(object);
        if (introspection == null) {
            throw new ValidationException("Bean introspection not found for the class: " + object.getClass());
        }
        return validate(introspection, object, groups);
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
    @SuppressWarnings("ConstantConditions")
    @NonNull
    public <T> Set<ConstraintViolation<T>> validate(@NonNull BeanIntrospection<T> introspection,
                                                    @NonNull T object,
                                                    @Nullable Class<?>... groups) {
        if (introspection == null) {
            throw new ValidationException("Passed object [" + object + "] cannot be introspected. Please annotate with @Introspected");
        }
        for (Class<?>[] groupSequence : findGroupSequences(groups)) {
            DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(object, groupSequence);
            doValidate(context, introspection, object);
            if (!context.overallViolations.isEmpty()) {
                return context.overallViolations;
            }
        }
        return Collections.emptySet();
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateProperty(@NonNull T object,
                                                            @NonNull String propertyName,
                                                            @Nullable Class<?>... groups) {
        ArgumentUtils.requireNonNull("object", object);
        ArgumentUtils.requireNonNull("propertyName", propertyName);
        final BeanIntrospection<T> introspection = getBeanIntrospection(object);
        if (introspection == null) {
            throw new ValidationException("Passed object [" + object + "] cannot be introspected. Please annotate with @Introspected");
        }

        final Optional<BeanProperty<T, Object>> property = introspection.getProperty(propertyName);
        if (property.isEmpty()) {
            return Collections.emptySet();
        }

        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(object, groups);

        visitProperty(context, object, property.get());

        return Collections.unmodifiableSet(context.overallViolations);
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateValue(@NonNull Class<T> beanType,
                                                         @NonNull String propertyName,
                                                         @Nullable Object value,
                                                         @Nullable Class<?>... groups) {
        ArgumentUtils.requireNonNull("beanType", beanType);
        ArgumentUtils.requireNonNull("propertyName", propertyName);

        final BeanIntrospection<T> introspection = getBeanIntrospection(beanType);
        if (introspection == null) {
            throw new ValidationException("Passed bean type [" + beanType + "] cannot be introspected. Please annotate with @Introspected");
        }

        final BeanProperty<T, Object> beanProperty = introspection.getProperty(propertyName)
                .orElseThrow(() -> new ValidationException("No property [" + propertyName + "] found on type: " + beanType));

        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(beanType, groups);

        context.addPropertyNode(beanProperty.getName());
        try {
            visitElement(context, null, beanProperty.asArgument(), value);
        } finally {
            context.removeLast();
        }

        return Collections.unmodifiableSet(context.overallViolations);
    }

    @NonNull
    @Override
    public Set<String> validatedAnnotatedElement(@NonNull AnnotatedElement element, @Nullable Object value) {
        ArgumentUtils.requireNonNull("element", element);
        if (!element.getAnnotationMetadata().hasStereotype(Constraint.class)) {
            return Collections.emptySet();
        }

        final DefaultConstraintValidatorContext<Object> context = new DefaultConstraintValidatorContext<>(value);

        Argument<Object> type = value != null ? Argument.of((Class<Object>) value.getClass(), element.getAnnotationMetadata()) : Argument.OBJECT_ARGUMENT;

        context.addPropertyNode(element.getName());

        try {
            visitElement(context, element, type, value);
        } finally {
            context.removeLast();
        }

        return context.overallViolations.stream().map(ConstraintViolation::getMessage).collect(Collectors.toUnmodifiableSet());
    }

    @NonNull
    @Override
    public <T> T createValid(@NonNull Class<T> beanType, Object... arguments) throws ConstraintViolationException {
        ArgumentUtils.requireNonNull("type", beanType);

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
                                                              @Nullable Class<?>... groups) {
        ArgumentUtils.requireNonNull("parameterValues", parameterValues);
        ArgumentUtils.requireNonNull("object", object);
        ArgumentUtils.requireNonNull("method", method);
        final Argument<?>[] arguments = method.getArguments();
        final int argLen = arguments.length;
        if (argLen != parameterValues.length) {
            throw new IllegalArgumentException("The method parameter array must have exactly " + argLen + " elements.");
        }

        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(object, parameterValues, groups);

        context.addMethodNode(method);
        try {
            validateParametersInternal(context, object, parameterValues, arguments, argLen);
        } finally {
            context.removeLast();
        }
        return Collections.unmodifiableSet(context.overallViolations);
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateParameters(@NonNull T object,
                                                              @NonNull ExecutableMethod method,
                                                              @NonNull Collection<MutableArgumentValue<?>> argumentValues,
                                                              @Nullable Class<?>... groups) {
        ArgumentUtils.requireNonNull("object", object);
        ArgumentUtils.requireNonNull("method", method);
        ArgumentUtils.requireNonNull("parameterValues", argumentValues);
        final Argument<?>[] arguments = method.getArguments();
        final int argLen = arguments.length;
        if (argLen != argumentValues.size()) {
            throw new IllegalArgumentException("The method parameter array must have exactly " + argLen + " elements.");
        }

        Object[] parameters = argumentValues.stream().map(ArgumentValue::getValue).toArray();

        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(object, parameters, groups);

        context.addMethodNode(method);
        try {
            validateParametersInternal(context, object, parameters, arguments, argLen);
        } finally {
            context.removeLast();
        }
        return Collections.unmodifiableSet(context.overallViolations);
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateParameters(@NonNull T object,
                                                              @NonNull Method method,
                                                              @NonNull Object[] parameterValues,
                                                              @Nullable Class<?>... groups) {
        ArgumentUtils.requireNonNull("method", method);
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
                                                               @Nullable Class<?>... groups) {
        ArgumentUtils.requireNonNull("method", method);
        ArgumentUtils.requireNonNull("object", object);
        return executionHandleLocator.findExecutableMethod(
                        method.getDeclaringClass(),
                        method.getName(),
                        method.getParameterTypes()
                ).map(executableMethod -> validateReturnValue(object, executableMethod, returnValue, groups))
                .orElse(Collections.emptySet());
    }

    @Override
    public @NonNull <T> Set<ConstraintViolation<T>> validateReturnValue(@NonNull T object,
                                                                        @NonNull ExecutableMethod<?, Object> executableMethod,
                                                                        @Nullable Object returnValue,
                                                                        @Nullable Class<?>... groups) {
        final ReturnType<Object> returnType = executableMethod.getReturnType();
        final DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(object, groups);

        boolean hasValid = executableMethod.hasStereotype(Valid.class);
        boolean hasConstraint = executableMethod.hasStereotype(Constraint.class);

        context.addMethodNode(executableMethod);
        context.addReturnValueNode();
        try {
            visitElement(context, object, returnType.asArgument(), returnValue, hasValid, hasConstraint);
        } finally {
            context.removeLast();
            context.removeLast();
        }

        return context.overallViolations;
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(@NonNull Constructor<? extends T> constructor,
                                                                         @NonNull Object[] parameterValues,
                                                                         @Nullable Class<?>... groups) {
        ArgumentUtils.requireNonNull("constructor", constructor);
        final Class<? extends T> declaringClass = constructor.getDeclaringClass();
        final BeanIntrospection<? extends T> introspection = getBeanIntrospection(declaringClass);
        return validateConstructorParameters(introspection, parameterValues);
    }

    @Override
    @NonNull
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(@NonNull BeanIntrospection<? extends T> introspection,
                                                                         @NonNull Object[] parameterValues,
                                                                         @Nullable Class<?>... groups) {
        ArgumentUtils.requireNonNull("introspection", introspection);
        final Class<? extends T> beanType = introspection.getBeanType();
        final Argument<?>[] constructorArguments = introspection.getConstructorArguments();
        return validateConstructorParameters(beanType, constructorArguments, parameterValues, groups);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(Class<? extends T> beanType,
                                                                         Argument<?>[] constructorArguments,
                                                                         @NonNull Object[] parameterValues,
                                                                         @Nullable Class<?>[] groups) {
        //noinspection ConstantConditions
        parameterValues = parameterValues != null ? parameterValues : ArrayUtils.EMPTY_OBJECT_ARRAY;
        final int argLength = constructorArguments.length;
        if (parameterValues.length != argLength) {
            throw new IllegalArgumentException("Expected exactly [" + argLength + "] constructor arguments");
        }
        DefaultConstraintValidatorContext<T> context = (DefaultConstraintValidatorContext<T>) new DefaultConstraintValidatorContext<>(beanType, constructorArguments, groups);

        context.addConstructorNode(beanType.getSimpleName(), constructorArguments);
        try {
            validateParametersInternal(context, null, parameterValues, constructorArguments, argLength);
        } finally {
            context.removeLast();
        }
        return Collections.unmodifiableSet(context.overallViolations);
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorReturnValue(@NonNull Constructor<? extends T> constructor,
                                                                          @NonNull T createdObject,
                                                                          @Nullable Class<?>... groups) {
        return validate(createdObject, groups);
    }

    @NonNull
    @Override
    public <T> Publisher<T> validatePublisher(@NonNull ReturnType<?> returnType,
                                              @NonNull Publisher<T> publisher,
                                              Class<?>... groups) {
        ArgumentUtils.requireNonNull("publisher", publisher);
        ArgumentUtils.requireNonNull("returnType", returnType);

        if (returnType.getTypeParameters().length == 0) {
            return publisher;
        }
        Argument<Object> typeParameter = returnType.getTypeParameters()[0];
        Argument<Publisher<T>> publisherArgument = Argument.of((Class<Publisher<T>>) publisher.getClass());

        Publisher<Object> output;
        if (Publishers.isSingle(returnType.getType())) {
            output = Mono.from(publisher).flatMap(value -> {
                Set<? extends ConstraintViolation<?>> violations = validatePublisherValue(publisherArgument, publisher, groups, typeParameter, value);
                return violations.isEmpty() ? Mono.just(value) :
                        Mono.error(new ConstraintViolationException(violations));
            });
        } else {
            output = Flux.from(publisher).flatMap(value -> {
                Set<? extends ConstraintViolation<?>> violations = validatePublisherValue(publisherArgument, publisher, groups, typeParameter, value);
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
                                                                                E value
    ) {
        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(publisher, groups);
        context.addReturnValueNode();
        try {
            validateIterableValue(context,
                    publisher,
                    "<publisher element>",
                    publisherArgument,
                    valueArgument,
                    value,
                    null, null, 0, true,
                    publisherArgument.getAnnotationMetadata().hasAnnotation(Valid.class) || valueArgument.getAnnotationMetadata().hasAnnotation(Valid.class),
                    valueArgument.getAnnotationMetadata().hasAnnotation(Constraint.class)
            );
        } finally {
            context.removeLast();
        }

        return context.overallViolations;
    }

    @NonNull
    @Override
    public <T> CompletionStage<T> validateCompletionStage(@NonNull CompletionStage<T> completionStage,
                                                          Class<?>... groups) {
        ArgumentUtils.requireNonNull("completionStage", completionStage);
        return completionStage.thenApply(t -> {
            final Set<ConstraintViolation<Object>> constraintViolations = validate(t, groups);
            if (!constraintViolations.isEmpty()) {
                throw new ConstraintViolationException(constraintViolations);
            }
            return t;
        });
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

        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(value);

        final Class<?> rootClass = injectionPoint.getDeclaringBean().getBeanType();

        context.addConstructorNode(
                rootClass.getName(), injectionPoint.getDeclaringBean().getConstructor().getArguments());

        context.addPropertyNode(argument.getName());
        try {
            visitElement(context, null, argument, value);
        } finally {
            context.removeLast();
        }

        failOnError(resolutionContext, context.overallViolations, rootClass);
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
            final DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(bean);
            final Class<?>[] interfaces = beanType.getInterfaces();
            if (ArrayUtils.isNotEmpty(interfaces)) {
                context.addConstructorNode(interfaces[0].getSimpleName());
            } else {
                context.addConstructorNode(beanType.getSimpleName());
            }
            for (ExecutableMethod<T, ?> executableMethod : executableMethods) {
                if (executableMethod.hasAnnotation(Property.class)) {
                    final boolean hasConstraint = executableMethod.hasStereotype(Constraint.class);
                    final boolean isValid = executableMethod.hasStereotype(Valid.class);
                    if (hasConstraint || isValid) {
                        final Object value = executableMethod.invoke(bean);
                        final ReturnType<Object> returnType = (ReturnType<Object>) executableMethod.getReturnType();

                        context.addPropertyNode(executableMethod.getName());
                        try {
                            visitElement(context, bean, returnType.asArgument(), value);
                        } finally {
                            context.removeLast();
                        }
                    }
                }
            }

            failOnError(resolutionContext, context.overallViolations, beanType);
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
        //noinspection ConstantConditions
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
        //noinspection ConstantConditions
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

    /**
     * Processes a method argument that is a publisher. Since the argument cannot be validated
     * at this exact time, the publisher is exchanged for another publisher that performs validation
     * and publishes the original items.
     * The method can convert both single and reactive publishers.
     *
     * @param context           validation context
     * @param argumentValues    the values of all the arguments
     * @param argumentIndex     the index of the publisher argument to be validated
     * @param publisherArgument argument corresponding to the parameter type
     * @param publisherArgument the type of the parameter
     * @param parameterValue    the provided value of the argument
     */
    private <R, E> void instrumentPublisherArgumentWithValidation(@NonNull DefaultConstraintValidatorContext<R> context,
                                                                  @NonNull Object[] argumentValues,
                                                                  int argumentIndex,
                                                                  @NonNull Argument<E> publisherArgument,
                                                                  E parameterValue) {
        final Publisher<?> publisher = Publishers.convertPublisher(conversionService, parameterValue, Publisher.class);
        DefaultConstraintValidatorContext<R> valueContext = context.copy();

        Publisher<?> objectPublisher;
        if (publisherArgument.isSpecifiedSingle()) {
            objectPublisher = Mono.from(publisher)
                    .flatMap(value -> {

                        validatePublishedValue(valueContext, argumentIndex, publisherArgument, parameterValue, value);

                        return valueContext.overallViolations.isEmpty() ? Mono.just(value) :
                                Mono.error(new ConstraintViolationException(valueContext.overallViolations));
                    });
        } else {
            objectPublisher = Flux.from(publisher).flatMap(value -> {

                validatePublishedValue(valueContext, argumentIndex, publisherArgument, parameterValue, value);

                return valueContext.overallViolations.isEmpty() ? Flux.just(value) :
                        Flux.error(new ConstraintViolationException(valueContext.overallViolations));
            });
        }
        argumentValues[argumentIndex] = Publishers.convertPublisher(conversionService, objectPublisher, publisherArgument.getType());
    }

    /**
     * Method used inside the {@link #instrumentPublisherArgumentWithValidation}.
     */
    private <R, E> void validatePublishedValue(@NonNull DefaultConstraintValidatorContext<R> context,
                                               int argumentIndex,
                                               @NonNull Argument<E> publisherArgument,
                                               E value,
                                               @NonNull Object publisherInstance) {
        // noinspection unchecked
        Argument<Object>[] typeParameters = publisherArgument.getTypeParameters();

        if (typeParameters.length == 0) {
            // No validation if no parameters
            return;
        }
        Argument<Object> valueArgument = typeParameters[0];

        // Create the parameter node and the container element node
        context.addParameterNode(publisherArgument.getName(), argumentIndex);
        context.addContainerElementNode("<publisher element>", value.getClass(), null, null, true, 0);
        try {
            // node is removed from context inside validateElement()
            visitElement(context, context.getRootBean(), valueArgument, publisherInstance);
        } finally {
            context.removeLast();
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
                                                                        E parameterValue) {
        final CompletionStage<?> completionStage = (CompletionStage<?>) parameterValue;

        final CompletionStage<?> validatedStage = completionStage.thenApply(value -> {
            DefaultConstraintValidatorContext<T> newContext = context.copy();

            // noinspection unchecked
            Argument<Object>[] typeParameters = completionStageArgument.getTypeParameters();

            if (typeParameters.length == 0) {
                // No validation if no parameters
                return value;
            }
            Argument<Object> valueArgument = typeParameters[0];

            // Create the parameter node and the container element node
            newContext.addParameterNode(completionStageArgument.getName(), argumentIndex);
            newContext.addContainerElementNode("<completion stage element>", parameterValue.getClass(), null, null, true, 0);
            try {
                // node is removed from context inside validateElement()
                visitElement(newContext, newContext.getRootBean(), valueArgument, value);
            } finally {
                newContext.removeLast();
            }

            if (!newContext.overallViolations.isEmpty()) {
                throw new ConstraintViolationException(newContext.overallViolations);
            }

            return value;
        });

        argumentValues[argumentIndex] = validatedStage;
    }

    private <T> void validateParametersInternal(@NonNull DefaultConstraintValidatorContext<T> context,
                                                @Nullable T bean,
                                                @NonNull Object[] parameters,
                                                @NonNull Argument<?>[] arguments,
                                                int argLen) {
        for (int parameterIndex = 0; parameterIndex < argLen; parameterIndex++) {
            Argument<Object> argument = (Argument<Object>) arguments[parameterIndex];
            final Class<Object> parameterType = argument.getType();

            Object parameterValue = parameters[parameterIndex];
            final boolean hasValue = parameterValue != null;

            final boolean isPublisher = hasValue && Publishers.isConvertibleToPublisher(parameterType);
            if (isPublisher) {
                instrumentPublisherArgumentWithValidation(context, parameters, parameterIndex, argument, parameterValue);
                continue;
            }

            final boolean isCompletionStage = hasValue && CompletionStage.class.isAssignableFrom(parameterType);
            if (isCompletionStage) {
                instrumentCompletionStageArgumentWithValidation(context, parameters, parameterIndex, argument, parameterValue);
                continue;
            }

            AnnotationMetadata annotationMetadata = argument.getAnnotationMetadata();
            boolean hasValid = annotationMetadata.hasStereotype(Valid.class);
            boolean hasConstraint = annotationMetadata.hasStereotype(Constraint.class);
            if (hasValid || hasConstraint) {
                context.addParameterNode(argument.getName(), parameterIndex);
                try {
                    visitElement(context,
                        bean,
                        argument,
                        parameterValue,
                        hasValid,
                        hasConstraint
                    );
                } finally {
                    context.removeLast();
                }
            }
        }
    }

    private <R, T> void doValidate(@NonNull DefaultConstraintValidatorContext<R> context,
                                   @NonNull BeanIntrospection<T> introspection,
                                   @NonNull T object) {

        visitElement(
                context,
                object,
                Argument.of(introspection.getBeanType(), introspection.getAnnotationMetadata()),
                object
        );

        for (BeanProperty<T, Object> property : introspection.getBeanProperties()) {
            visitProperty(context, object, property);
        }
    }

    private <R, T> void visitProperty(DefaultConstraintValidatorContext<R> context, T object, BeanProperty<T, Object> property) {
        if (property.isWriteOnly()) {
            return;
        }

        context.addPropertyNode(property.getName());
        try {
            visitElement(
                    context,
                    object,
                    property.asArgument(),
                    property.get(object)
            );
        } finally {
            context.removeLast();
        }
    }

    private <R> boolean canCascade(@NonNull DefaultConstraintValidatorContext<R> context,
                                   Object propertyValue) {
        final boolean isReachable = traversableResolver.isReachable(
                propertyValue,
                context.currentPath.nodes.peekLast(),
                context.getRootClass(),
                context.currentPath,
                ElementType.FIELD
        );
        if (!isReachable) {
            return false;
        }

        return traversableResolver.isCascadable(
                propertyValue,
                context.currentPath.nodes.peekLast(),
                context.getRootClass(),
                context.currentPath,
                ElementType.FIELD
        );
    }

    private <R, E> void visitElement(DefaultConstraintValidatorContext<R> context,
                                     Object bean,
                                     Argument<E> annotatedElementType,
                                     E elementValue) {
        visitElement(context,
                bean,
                annotatedElementType,
                elementValue,
                annotatedElementType.getAnnotationMetadata().hasStereotype(Valid.class),
                annotatedElementType.getAnnotationMetadata().hasStereotype(Constraint.class)
        );
    }

    private <R, E> void visitElement(DefaultConstraintValidatorContext<R> context,
                                     Object leftBean,
                                     Argument<E> elementArgument,
                                     E elementValue,
                                     boolean hasValid,
                                     boolean hasConstraint) {

        boolean iterableValidated = visitIterable(context, leftBean, elementArgument, elementValue);

        if (hasConstraint) {
            validateConstrains(context, leftBean, elementArgument, elementValue);
        }

        if (hasValid && !iterableValidated) {
            propagateValidation(context, leftBean, elementArgument, elementValue);
        }
    }

    private <R, E> void propagateValidation(DefaultConstraintValidatorContext<R> context,
                                            Object leftBean,
                                            Argument<E> elementType,
                                            E elementValue) {

        // otherwise it needs cascading as a bean
        if (elementValue == null || context.validatedObjects.contains(elementValue)) {
            return;
        }
        final BeanIntrospection<E> beanIntrospection = getBeanIntrospection(elementValue, elementType.getType());
        if (beanIntrospection == null) {
            // Error if not introspected
            DefaultConstraintViolation<R> violation = createIntrospectionConstraintViolation(context, leftBean, elementType, elementValue);
            context.overallViolations.add(violation);
            return;
        }
        if (canCascade(context, elementValue)) {
            context.validatedObjects.add(elementValue);
            doValidate(context, beanIntrospection, elementValue);
        }
    }

    /**
     * Cascade to the values of iterable (leftBean having a value extractor).
     * All the parameters match ones defined in validateCascadeElement(...) method.
     *
     * @return - whether element was an iterable
     */
    private <R, I> boolean visitIterable(DefaultConstraintValidatorContext<R> context,
                                         Object leftBean,
                                         Argument<I> iterableArgument,
                                         I iterable) {
        // Check if it has valueExtractor
        final Optional<? extends ValueExtractor<I>> opt = valueExtractorRegistry.findValueExtractor(iterableArgument.getType());

        if (opt.isEmpty()) {
            return false;
        }
        if (iterable == null) {
            // Does not require validation (with @Valid annotation)
            return true;
        }

        Argument<?>[] arguments = iterableArgument.getTypeParameters();

        // Check if its values need validation
        final boolean keyHasValid;
        final boolean valueHasValid;
        final boolean keyHasConstraint;
        final boolean valueHasConstraint;
        boolean iterableIsValid = iterableArgument.getAnnotationMetadata().hasStereotype(Valid.class);
        if (arguments.length == 1) {
            // Iterable with one generic parameter
            keyHasConstraint = false;
            keyHasValid = false;
            valueHasValid = iterableIsValid || arguments[0].getAnnotationMetadata().hasStereotype(Valid.class);
            valueHasConstraint = arguments[0].getAnnotationMetadata().hasStereotype(Constraint.class);
        } else if (arguments.length == 2) {
            keyHasValid = iterableIsValid || arguments[0].getAnnotationMetadata().hasStereotype(Valid.class);
            keyHasConstraint = arguments[0].getAnnotationMetadata().hasStereotype(Constraint.class);
            valueHasValid = iterableIsValid || arguments[1].getAnnotationMetadata().hasStereotype(Valid.class);
            valueHasConstraint = arguments[1].getAnnotationMetadata().hasStereotype(Constraint.class);
        } else {
            // Some optional
            keyHasConstraint = false;
            keyHasValid = false;
            valueHasConstraint = iterableArgument.getAnnotationMetadata().hasStereotype(Constraint.class);
            valueHasValid = iterableIsValid;
        }

        // extract and validate values
        opt.get().extractValues(iterable, new ValueExtractor.ValueReceiver() {
            @Override
            public void value(String nodeName, Object value) {
                Argument<Object> argument = asArgument(value);
                validateIterableValue(context, leftBean, nodeName, iterableArgument, argument, value, null, null, 0, false, valueHasValid, valueHasConstraint);
            }

            @Override
            public void iterableValue(String nodeName, Object iterableValue) {
                Argument<Object> argument = asArgument(iterableValue);
                validateIterableValue(context, leftBean, nodeName, iterableArgument, argument, iterableValue, null, null, 0, true, valueHasValid, valueHasConstraint);
            }

            @Override
            public void indexedValue(String nodeName, int i, Object iterableValue) {
                Argument<Object> argument = asArgument(iterableValue);
                validateIterableValue(context, leftBean, nodeName, iterableArgument, argument, iterableValue, i, null, 0, true, valueHasValid, valueHasConstraint);
            }

            @Override
            public void keyedValue(String nodeName, Object key, Object keyedValue) {
                Argument<Object> argumentKey = (Argument<Object>) arguments[0];
                validateIterableValue(context, leftBean, "<map key>", iterableArgument, argumentKey, key, null, key, 0, true, keyHasValid, keyHasConstraint);

                Argument<Object> argumentValue = (Argument<Object>) arguments[1];
                validateIterableValue(context, leftBean, "<map value>", iterableArgument, argumentValue, keyedValue, null, key, 1, true, valueHasValid, valueHasConstraint);
            }

            private Argument<Object> asArgument(Object value) {
                Argument<Object> argument;
                if (arguments.length == 0) {
                    argument = Argument.of(value == null ? Object.class : (Class<Object>) value.getClass(), iterableArgument.getAnnotationMetadata());
                } else {
                    argument = (Argument<Object>) arguments[0];
                }
                return argument;
            }

        });

        return true;
    }

    private <R, I, E> void validateIterableValue(DefaultConstraintValidatorContext<R> context,
                                                 Object leftBean,
                                                 String name,
                                                 Argument<I> iterableArgument,
                                                 Argument<E> valueArgument,
                                                 E value,
                                                 Integer index,
                                                 Object key,
                                                 Integer typeArgumentIndex,
                                                 boolean isInIterable,
                                                 boolean hasValid,
                                                 boolean hasConstraint) {

        if (isInIterable) {
            context.addContainerElementNode(name, iterableArgument.getType(), index, key, isInIterable, typeArgumentIndex);
        }

        visitElement(context, leftBean, valueArgument, value, hasValid, hasConstraint);

        if (isInIterable) {
            context.removeLast();
        }
    }

    private <R, E> void validateConstrains(DefaultConstraintValidatorContext<R> context,
                                           @Nullable Object leftBean,
                                           @NonNull Argument<E> elementArgument,
                                           @Nullable E elementValue) {

        for (Class<? extends Annotation> constraintType : elementArgument.getAnnotationMetadata().getAnnotationTypesByStereotype(Constraint.class)) {
            valueConstraintOnElement(context, leftBean, elementArgument, elementValue, constraintType);
        }
    }

    private <R, E, A extends Annotation> void valueConstraintOnElement(DefaultConstraintValidatorContext<R> context,
                                                                       @Nullable Object leafBean,
                                                                       Argument<E> elementArgument,
                                                                       @Nullable E elementValue,
                                                                       Class<A> constraintType) {

        boolean isDefaultGroup = context.groups == DEFAULT_GROUPS || context.groups.contains(Default.class);
        List<AnnotationValue<A>> annotationConstraints = elementArgument.getAnnotationMetadata().getAnnotationValuesByType(constraintType);
        Set<AnnotationValue<A>> constraints = CollectionUtils.newLinkedHashSet(annotationConstraints.size());
        for (AnnotationValue<A> annotationValue : annotationConstraints) {
            final Class<?>[] classValues = annotationValue.classValues("groups");
            if (isDefaultGroup && ArrayUtils.isEmpty(classValues)) {
                constraints.add(annotationValue);
            } else {
                final List<Class<?>> constraintGroups = Arrays.asList(classValues);
                if (context.groups.stream().anyMatch(constraintGroups::contains)) {
                    constraints.add(annotationValue);
                }
            }
        }

        validateConstraint(context, leafBean, elementArgument.getAnnotationMetadata(), elementValue, constraintType, constraints, elementArgument.getType());
    }

    private <R, E, A extends Annotation> void validateConstraint(DefaultConstraintValidatorContext<R> context,
                                                                 Object leafBean,
                                                                 AnnotationMetadata annotationMetadata,
                                                                 E elementValue,
                                                                 Class<A> constraintType,
                                                                 Set<AnnotationValue<A>> constraints,
                                                                 Class<E> type) {

        ConstraintValidator<A, E> validator = constraintValidatorRegistry.findConstraintValidator(constraintType, type).orElse(null);
        if (validator == null) {
            return;
        }
        for (AnnotationValue<A> annotationValue : constraints) {
            if (validator.isValid(elementValue, annotationValue, context)) {
                continue;
            }
            final String messageTemplate = buildMessageTemplate(context, annotationValue, annotationMetadata);
            final Map<String, Object> variables = newConstraintVariables(annotationValue, elementValue, annotationMetadata);
            final String message = messageSource.interpolate(messageTemplate, MessageSource.MessageContext.of(variables));
            final ConstraintDescriptor<?> constraintDescriptor = new DefaultConstraintDescriptor<>(annotationMetadata, constraintType, annotationValue);

            DefaultConstraintViolation<R> constraintViolation = new DefaultConstraintViolation<>(
                    context.getRootBean(),
                    context.getRootClass(),
                    leafBean,
                    elementValue,
                    message,
                    messageTemplate,
                    new PathImpl(context.currentPath),
                    constraintDescriptor,
                    context.executableParameterValues
            );

            context.overallViolations.add(constraintViolation);
            context.messageTemplate(null);
        }
    }

    private <A extends Annotation> Map<String, Object> newConstraintVariables(AnnotationValue<A> annotationValue,
                                                                              @Nullable Object propertyValue,
                                                                              AnnotationMetadata annotationMetadata) {
        final Map<?, ?> values = annotationValue.getValues();
        Map<String, Object> variables = CollectionUtils.newLinkedHashMap(values.size());
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            variables.put(entry.getKey().toString(), entry.getValue());
        }
        variables.put("validatedValue", propertyValue);
        final Map<CharSequence, Object> defaultValues = annotationMetadata.getDefaultValues(annotationValue.getAnnotationName());
        for (Map.Entry<CharSequence, Object> entry : defaultValues.entrySet()) {
            final String n = entry.getKey().toString();
            if (!variables.containsKey(n)) {
                final Object v = entry.getValue();
                if (v != null) {
                    variables.put(n, v);
                }
            }
        }
        return variables;
    }

    private <R> String buildMessageTemplate(final DefaultConstraintValidatorContext<R> context,
                                            final AnnotationValue<?> annotationValue,
                                            final AnnotationMetadata annotationMetadata) {
        return context.getMessageTemplate()
                .orElseGet(() -> annotationValue.stringValue("message")
                        .orElseGet(() -> annotationMetadata.getDefaultValue(annotationValue.getAnnotationName(), "message", String.class)
                                .orElse("{" + annotationValue.getAnnotationName() + ".message}")));
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

    @NonNull
    private <R, T, E> DefaultConstraintViolation<R> createIntrospectionConstraintViolation(DefaultConstraintValidatorContext<R> context,
                                                                                           T leftBean,
                                                                                           Argument<E> invalidValueType,
                                                                                           E invalidValue) {
        final String messageTemplate = context.getMessageTemplate().orElseGet(() -> "{" + Introspected.class.getName() + ".message}");
        return new DefaultConstraintViolation<>(
                context.getRootBean(),
                context.getRootClass(),
                leftBean,
                invalidValue,
                messageSource.interpolate(messageTemplate, MessageSource.MessageContext.of(Collections.singletonMap("type", invalidValueType.getType().getName()))),
                messageTemplate,
                new PathImpl(context.currentPath),
                null,
                context.executableParameterValues);
    }

    private List<Class<?>[]> findGroupSequences(Class<?>[] groups) {
        if (groups.length == 0 || groups.length > 1) {
            return Collections.singletonList(groups);
        }
        List<Class<?>> groupSequence = GROUP_SEQUENCES.computeIfAbsent(groups[0], group -> {
            return beanIntrospector.findIntrospection(group).stream()
                    .<Class<?>>flatMap(introspection -> Arrays.stream(introspection.classValues(GroupSequence.class)))
                    .toList();
        });
        if (groupSequence.isEmpty()) {
            return Collections.singletonList(groups);
        }
        return groupSequence.stream().map(c -> new Class<?>[]{c}).toList();
    }

    /**
     * The context object.
     *
     * @param <R> The root bean type
     */
    private final class DefaultConstraintValidatorContext<R> implements ConstraintValidatorContext {
        private final R rootBean;
        @Nullable
        private final Class<R> rootClass;
        @Nullable
        private final Object[] executableParameterValues;
        private final Set<Object> validatedObjects = new HashSet<>(20);
        private final PathImpl currentPath;
        private final List<Class<?>> groups;
        private String messageTemplate = null;
        private final Set<ConstraintViolation<R>> overallViolations;

        private DefaultConstraintValidatorContext(R rootBean, Class<R> rootClass, Class<?>... groups) {
            this(rootBean, rootClass, null, new PathImpl(), new LinkedHashSet<>(), groups);
        }

        private DefaultConstraintValidatorContext(R rootBean, Class<?>... groups) {
            this(rootBean, (Class<R>) rootBean.getClass(), null, new PathImpl(), new LinkedHashSet<>(), groups);
        }

        private DefaultConstraintValidatorContext(R rootBean, Object[] executableParameterValues, Class<?>... groups) {
            this(rootBean, (Class<R>) rootBean.getClass(), executableParameterValues, new PathImpl(), new LinkedHashSet<>(), groups);
        }

        private DefaultConstraintValidatorContext(R rootBean,
                                                  Class<R> rootClass,
                                                  Object[] executableParameterValues,
                                                  PathImpl path,
                                                  Set<ConstraintViolation<R>> overallViolations,
                                                  Class<?>... groups) {
            this.rootBean = rootBean;
            this.rootClass = rootClass;
            this.executableParameterValues = executableParameterValues;
            if (ArrayUtils.isNotEmpty(groups)) {
                sanityCheckGroups(groups);

                List<Class<?>> groupList = new ArrayList<>();
                for (Class<?> group : groups) {
                    addInheritedGroups(group, groupList);
                }
                this.groups = Collections.unmodifiableList(groupList);
            } else {
                this.groups = DEFAULT_GROUPS;
            }
            this.currentPath = path != null ? path : new PathImpl();
            this.overallViolations = overallViolations;
        }

        private DefaultConstraintValidatorContext(Class<R> rootClass, Class<?>... groups) {
            this(null, rootClass, groups);
        }

        private void sanityCheckGroups(Class<?>[] groups) {
            ArgumentUtils.requireNonNull("groups", groups);

            for (Class<?> clazz : groups) {
                if (clazz == null) {
                    throw new IllegalArgumentException("Validation groups must be non-null");
                }
                if (!clazz.isInterface()) {
                    throw new IllegalArgumentException(
                            "Validation groups must be interfaces. " + clazz.getName() + " is not.");
                }
            }
        }

        @Nullable
        @Override
        public R getRootBean() {
            return rootBean;
        }

        public Class<R> getRootClass() {
            return rootClass;
        }

        private void addInheritedGroups(Class<?> group, List<Class<?>> groups) {
            if (!groups.contains(group)) {
                groups.add(group);
            }

            for (Class<?> inheritedGroup : group.getInterfaces()) {
                addInheritedGroups(inheritedGroup, groups);
            }
        }

        @NonNull
        @Override
        public ClockProvider getClockProvider() {
            return clockProvider;
        }

        @Override
        public void messageTemplate(@Nullable final String messageTemplate) {
            this.messageTemplate = messageTemplate;
        }

        Optional<String> getMessageTemplate() {
            return Optional.ofNullable(messageTemplate);
        }

        Path.Node addPropertyNode(String name) {
            final DefaultPropertyNode node = new DefaultPropertyNode(name);
            currentPath.nodes.add(node);
            return node;
        }

        Path.Node addParameterNode(String name, int index) {
            final DefaultParameterNode node = new DefaultParameterNode(name, index);
            currentPath.nodes.add(node);
            return node;
        }

        Path.Node addReturnValueNode() {
            final DefaultReturnValueNode returnValueNode = new DefaultReturnValueNode("<return value>");
            currentPath.nodes.add(returnValueNode);
            return returnValueNode;
        }

        Path.Node addContainerElementNode(String name,
                                          Class<?> containerClass,
                                          Integer index,
                                          Object key,
                                          boolean isInIterable,
                                          Integer typeArgumentIndex) {
            final DefaultContainerElementNode node = new DefaultContainerElementNode(name, containerClass, index, key, isInIterable, typeArgumentIndex);
            currentPath.nodes.add(node);
            return node;
        }

        void removeLast() {
            currentPath.nodes.removeLast();
        }

        Path.Node addMethodNode(MethodReference<?, ?> reference) {
            final DefaultMethodNode methodNode = new DefaultMethodNode(reference);
            currentPath.nodes.add(methodNode);
            return methodNode;
        }

        Path.Node addConstructorNode(String simpleName, Argument<?>... constructorArguments) {
            final DefaultConstructorNode node = new DefaultConstructorNode(new MethodReference<>() {

                @Override
                public Argument[] getArguments() {
                    return constructorArguments;
                }

                @Override
                public Method getTargetMethod() {
                    return null;
                }

                @Override
                public ReturnType<Object> getReturnType() {
                    return null;
                }

                @Override
                public Class getDeclaringType() {
                    return null;
                }

                @Override
                public String getMethodName() {
                    return simpleName;
                }
            });
            currentPath.nodes.add(node);
            return node;
        }

        DefaultConstraintValidatorContext<R> copy() {
            return new DefaultConstraintValidatorContext<>(rootBean, rootClass, executableParameterValues, new PathImpl(currentPath), new LinkedHashSet<>(overallViolations));
        }
    }

    /**
     * Path implementation.
     */
    private static final class PathImpl implements Path {

        final Deque<Node> nodes;

        /**
         * Copy constructor.
         *
         * @param nodes The nodes
         */
        private PathImpl(PathImpl nodes) {
            this.nodes = new LinkedList<>(nodes.nodes);
        }

        private PathImpl() {
            this.nodes = new LinkedList<>();
        }

        @Override
        public Iterator<Node> iterator() {
            return nodes.iterator();
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            final Iterator<Node> i = nodes.iterator();
            boolean firstNode = true;

            while (i.hasNext()) {
                final Node node = i.next();

                if (node.getKind() == ElementKind.CONTAINER_ELEMENT) {
                    if (node.isInIterable()) {
                        builder.append('[');
                        if (node.getIndex() != null) {
                            builder.append(node.getIndex());
                        } else if (node.getKey() != null) {
                            builder.append(node.getKey());
                        }
                        builder.append(']');
                    }

                    builder.append(node.getName());
                } else {
                    builder.append(firstNode ? "" : ".");
                    builder.append(node.getName());
                }

                firstNode = false;
            }
            return builder.toString();
        }
    }

    /**
     * Default node implementation.
     */
    private abstract static class DefaultNode implements Path.Node {
        protected final String name;

        public DefaultNode(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isInIterable() {
            return false;
        }

        @Override
        public Integer getIndex() {
            return null;
        }

        @Override
        public Object getKey() {
            return null;
        }

        @Override
        public String toString() {
            return name;
        }

        @Override
        public <T extends Path.Node> T as(Class<T> nodeType) {
            if (nodeType.isInstance(this)) {
                return nodeType.cast(this);
            }
            throw new UnsupportedOperationException("Unwrapping is unsupported by this implementation");
        }
    }

    /**
     * Default property node implementation.
     */
    private static final class DefaultPropertyNode extends DefaultNode implements Path.PropertyNode {
        public DefaultPropertyNode(String name) {
            super(name);
        }

        @Override
        public Class<?> getContainerClass() {
            return null;
        }

        @Override
        public Integer getTypeArgumentIndex() {
            return null;
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.PROPERTY;
        }
    }

    /**
     * Method node implementation.
     */
    private static class DefaultMethodNode extends DefaultNode implements Path.MethodNode {

        private final MethodReference<?, ?> methodReference;

        public DefaultMethodNode(MethodReference<?, ?> methodReference) {
            super(methodReference.getMethodName());
            this.methodReference = methodReference;
        }

        @Override
        public List<Class<?>> getParameterTypes() {
            return Arrays.asList(methodReference.getArgumentTypes());
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.METHOD;
        }
    }

    /**
     * Default constructor node.
     */
    private static final class DefaultConstructorNode extends DefaultMethodNode implements Path.ConstructorNode {
        public DefaultConstructorNode(MethodReference<Object, Object> methodReference) {
            super(methodReference);
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.CONSTRUCTOR;
        }
    }

    /**
     * Default parameter node implementation.
     */
    private static final class DefaultParameterNode extends DefaultNode implements Path.ParameterNode {
        private final int parameterIndex;

        public DefaultParameterNode(@NonNull String name, int parameterIndex) {
            super(name);
            this.parameterIndex = parameterIndex;
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.PARAMETER;
        }

        @Override
        public int getParameterIndex() {
            return parameterIndex;
        }
    }

    /**
     * Default Return value node implementation.
     */
    private static final class DefaultReturnValueNode extends DefaultNode implements Path.ReturnValueNode {

        public DefaultReturnValueNode(String name) {
            super(name);
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.RETURN_VALUE;
        }
    }

    /**
     * Default container element node implementation.
     */
    private static final class DefaultContainerElementNode extends DefaultNode implements Path.ContainerElementNode {
        private final Class<?> containerClass;
        private final Integer index;
        private final Object key;
        private final boolean isInIterable;
        private final Integer typeArgumentIndex;

        public DefaultContainerElementNode(@Nullable
                                           String name,
                                           Class<?> containerClass,
                                           @Nullable
                                           Integer index,
                                           @Nullable
                                           Object key,
                                           boolean isInIterable,
                                           @Nullable
                                           Integer typeArgumentIndex) {
            super(name);
            this.containerClass = containerClass;
            this.index = index;
            this.key = key;
            this.isInIterable = isInIterable;
            this.typeArgumentIndex = typeArgumentIndex;
        }

        @Override
        public Class<?> getContainerClass() {
            return containerClass;
        }

        @Override
        public Integer getTypeArgumentIndex() {
            return typeArgumentIndex;
        }

        @Override
        public boolean isInIterable() {
            return isInIterable;
        }

        @Override
        public Integer getIndex() {
            return index;
        }

        @Override
        public Object getKey() {
            return key;
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.CONTAINER_ELEMENT;
        }
    }

    /**
     * Default implementation of {@link ConstraintViolation}.
     *
     * @param rootBean                  the root bean given for validation
     * @param rootBeanClass             the type of the root bean
     * @param leafBean                  the bean that failed validation
     * @param invalidValue              the value of the leaf bean
     * @param message                   message
     * @param messageTemplate           the template used for message
     * @param path                      the path to the leaf bean
     * @param constraintDescriptor      the descriptor of constraint for which validation failed
     * @param executableParameterValues the arguments provided to method if executable was validated
     * @param <T>                       The bean type.
     */
    private record DefaultConstraintViolation<T>(
            @Nullable T rootBean,
            @Nullable Class<T> rootBeanClass,
            Object leafBean,
            Object invalidValue,
            String message,
            String messageTemplate,
            Path path,
            ConstraintDescriptor<?> constraintDescriptor,
            @Nullable Object[] executableParameterValues
    ) implements ConstraintViolation<T> {

        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public String getMessageTemplate() {
            return messageTemplate;
        }

        @Override
        public T getRootBean() {
            return rootBean;
        }

        @Override
        public Class<T> getRootBeanClass() {
            return rootBeanClass;
        }

        @Override
        public Object getLeafBean() {
            return leafBean;
        }

        @Override
        public Object[] getExecutableParameters() {
            return Objects.requireNonNullElse(
                    executableParameterValues,
                    ArrayUtils.EMPTY_OBJECT_ARRAY
            );
        }

        @Override
        public Object getExecutableReturnValue() {
            return null;
        }

        @Override
        public Path getPropertyPath() {
            return path;
        }

        @Override
        public Object getInvalidValue() {
            return invalidValue;
        }

        @Override
        public ConstraintDescriptor<?> getConstraintDescriptor() {
            return constraintDescriptor;
        }

        @Override
        public <U> U unwrap(Class<U> type) {
            throw new UnsupportedOperationException("Unwrapping is unsupported by this implementation");
        }

        @Override
        public String toString() {
            return "DefaultConstraintViolation{" +
                    "rootBean=" + rootBeanClass +
                    ", invalidValue=" + invalidValue +
                    ", path=" + path +
                    '}';
        }
    }

    /**
     * An empty descriptor with no constraints.
     *
     * @param elementClass the class of element
     */
    private record EmptyDescriptor(
            Class<?> elementClass
    ) implements BeanDescriptor, ElementDescriptor.ConstraintFinder {

        @Override
        public boolean isBeanConstrained() {
            return false;
        }

        @Override
        public PropertyDescriptor getConstraintsForProperty(String propertyName) {
            return null;
        }

        @Override
        public Set<PropertyDescriptor> getConstrainedProperties() {
            return Collections.emptySet();
        }

        @Override
        public MethodDescriptor getConstraintsForMethod(String methodName, Class<?>... parameterTypes) {
            return null;
        }

        @Override
        public Set<MethodDescriptor> getConstrainedMethods(MethodType methodType, MethodType... methodTypes) {
            return Collections.emptySet();
        }

        @Override
        public ConstructorDescriptor getConstraintsForConstructor(Class<?>... parameterTypes) {
            return null;
        }

        @Override
        public Set<ConstructorDescriptor> getConstrainedConstructors() {
            return Collections.emptySet();
        }

        @Override
        public boolean hasConstraints() {
            return false;
        }

        @Override
        public Class<?> getElementClass() {
            return elementClass;
        }

        @Override
        public ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return this;
        }

        @Override
        public ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return Collections.emptySet();
        }

        @Override
        public ConstraintFinder findConstraints() {
            return this;
        }
    }
}
