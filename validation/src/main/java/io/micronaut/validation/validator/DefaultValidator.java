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
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ArgumentValue;
import io.micronaut.core.type.MutableArgumentValue;
import io.micronaut.core.type.ReturnType;
import io.micronaut.core.type.*;
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.InjectionPoint;
import io.micronaut.inject.MethodReference;
import io.micronaut.inject.annotation.AnnotatedElementValidator;
import io.micronaut.inject.validation.BeanDefinitionValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import io.micronaut.validation.validator.constraints.ConstraintValidatorRegistry;
import io.micronaut.validation.validator.extractors.ValueExtractorRegistry;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.validation.ClockProvider;
import javax.validation.Constraint;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.ElementKind;
import javax.validation.Path;
import javax.validation.TraversableResolver;
import javax.validation.Valid;
import javax.validation.ValidationException;
import javax.validation.groups.Default;
import javax.validation.metadata.BeanDescriptor;
import javax.validation.metadata.ConstraintDescriptor;
import javax.validation.metadata.ConstructorDescriptor;
import javax.validation.metadata.ElementDescriptor;
import javax.validation.metadata.MethodDescriptor;
import javax.validation.metadata.MethodType;
import javax.validation.metadata.PropertyDescriptor;
import javax.validation.metadata.Scope;
import javax.validation.valueextraction.ValueExtractor;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
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

    private static final List<Class<?>> DEFAULT_GROUPS = Collections.singletonList(Default.class);
    private final ConstraintValidatorRegistry constraintValidatorRegistry;
    private final ClockProvider clockProvider;
    private final ValueExtractorRegistry valueExtractorRegistry;
    private final TraversableResolver traversableResolver;
    private final ExecutionHandleLocator executionHandleLocator;
    private final MessageSource messageSource;

    /**
     * Default constructor.
     *
     * @param configuration The validator configuration
     */
    protected DefaultValidator(
            @NonNull ValidatorConfiguration configuration) {
        ArgumentUtils.requireNonNull("configuration", configuration);
        this.constraintValidatorRegistry = configuration.getConstraintValidatorRegistry();
        this.clockProvider = configuration.getClockProvider();
        this.valueExtractorRegistry = configuration.getValueExtractorRegistry();
        this.traversableResolver = configuration.getTraversableResolver();
        this.executionHandleLocator = configuration.getExecutionHandleLocator();
        this.messageSource = configuration.getMessageSource();
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validate(@NonNull T object, @Nullable Class<?>... groups) {
        ArgumentUtils.requireNonNull("object", object);
        final BeanIntrospection<T> introspection = (BeanIntrospection<T>) getBeanIntrospection(object);
        if (introspection == null) {
            return Collections.emptySet();
        }
        return validate(introspection, object, groups);
    }

    /**
     * Validate the given introspection and object.
     * @param introspection The introspection
     * @param object The object
     * @param groups The groups
     * @param <T> The object type
     * @return The constraint violations
     */
    @Override
    @SuppressWarnings("ConstantConditions")
    @NonNull
    public <T> Set<ConstraintViolation<T>> validate(
        @NonNull BeanIntrospection<T> introspection, @NonNull T object, @Nullable Class<?>... groups
    ) {
        if (introspection == null) {
            throw new ValidationException("Passed object [" + object + "] cannot be introspected. Please annotate with @Introspected");
        }
        DefaultConstraintValidatorContext context = new DefaultConstraintValidatorContext(object, groups);
        @SuppressWarnings("unchecked")
        final Collection<? extends BeanProperty<Object, Object>> constrainedProperties =
                ((BeanIntrospection<Object>) introspection).getIndexedProperties(Constraint.class);
        @SuppressWarnings("unchecked")
        final Collection<BeanProperty<Object, Object>> cascadeProperties =
                ((BeanIntrospection<Object>) introspection).getBeanProperties().stream()
                    .filter(p -> p.hasStereotype(Valid.class) || doesHaveValidatedTypeParameters(context, p))
                    .collect(Collectors.toList());

        final List<Class<? extends Annotation>> pojoConstraints = introspection
            .getAnnotationTypesByStereotype(Constraint.class);

        if (CollectionUtils.isNotEmpty(constrainedProperties)
                || CollectionUtils.isNotEmpty(cascadeProperties)
                || CollectionUtils.isNotEmpty(pojoConstraints)) {

            Set<ConstraintViolation<T>> overallViolations = new HashSet<>(5);
            return doValidate(context, overallViolations, object, introspection, object,
                constrainedProperties, cascadeProperties, pojoConstraints
            );
        }
        return Collections.emptySet();
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateProperty(
            @NonNull T object,
            @NonNull String propertyName,
            @Nullable Class<?>... groups
    ) {
        ArgumentUtils.requireNonNull("object", object);
        ArgumentUtils.requireNonNull("propertyName", propertyName);
        final BeanIntrospection<Object> introspection = getBeanIntrospection(object);
        if (introspection == null) {
            throw new ValidationException("Passed object [" + object + "] cannot be introspected. Please annotate with @Introspected");
        }

        final Optional<BeanProperty<Object, Object>> property = introspection.getProperty(propertyName);

        if (property.isPresent()) {
            final BeanProperty<Object, Object> constrainedProperty = property.get();
            DefaultConstraintValidatorContext context = new DefaultConstraintValidatorContext(object, groups);
            Set overallViolations = new HashSet<>(5);
            final Object propertyValue = constrainedProperty.get(object);

            //noinspection unchecked
            final Class<Object> rootClass = (Class<Object>) object.getClass();

            Path.Node node = context.addPropertyNode(constrainedProperty.getName());
            validateElement(context, overallViolations, object,
                rootClass, constrainedProperty, object, constrainedProperty.getType(),
                propertyValue, node, false, false, null);

            //noinspection unchecked
            return Collections.unmodifiableSet(overallViolations);
        }

        return Collections.emptySet();
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateValue(
            @NonNull Class<T> beanType,
            @NonNull String propertyName,
            @Nullable Object value,
            @Nullable Class<?>... groups) {
        ArgumentUtils.requireNonNull("beanType", beanType);
        ArgumentUtils.requireNonNull("propertyName", propertyName);

        final BeanIntrospection<Object> introspection = getBeanIntrospection(beanType);
        if (introspection == null) {
            throw new ValidationException("Passed bean type [" + beanType + "] cannot be introspected. Please annotate with @Introspected");
        }

        final BeanProperty<Object, Object> beanProperty = introspection.getProperty(propertyName)
                .orElseThrow(() -> new ValidationException("No property [" + propertyName + "] found on type: " + beanType));

        final Set overallViolations = new HashSet<>(5);
        final DefaultConstraintValidatorContext context = new DefaultConstraintValidatorContext(groups);

        // create node, that will be removed inside validateElement()
        Path.Node node = context.addPropertyNode(beanProperty.getName());
        //noinspection unchecked
        validateElement(context, overallViolations, null, (Class<Object>) beanType, beanProperty, null,
            beanProperty.getType(), value, node);

        //noinspection unchecked
        return Collections.unmodifiableSet(overallViolations);
    }

    @NonNull
    @Override
    public Set<String> validatedAnnotatedElement(
        @NonNull AnnotatedElement element, @Nullable Object value
    ) {
        ArgumentUtils.requireNonNull("element", element);
        if (!element.getAnnotationMetadata().hasStereotype(Constraint.class)) {
            return Collections.emptySet();
        }

        final Set<ConstraintViolation<Object>> overallViolations = new HashSet<>(5);
        final DefaultConstraintValidatorContext context = new DefaultConstraintValidatorContext();

        Class<Object> type = value != null ? (Class<Object>) value.getClass() : Object.class;
        // create node, that will be removed inside validateElement()
        Path.Node node = context.addPropertyNode(element.getName());
        validateElement(context, overallViolations, element, null, element, element, type, value, node);

        return Collections.unmodifiableSet(overallViolations.stream()
                .map(ConstraintViolation::getMessage).collect(Collectors.toSet()));
    }

    @NonNull
    @Override
    public <T> T createValid(@NonNull Class<T> beanType, Object... arguments) throws ConstraintViolationException {
        ArgumentUtils.requireNonNull("type", beanType);

        @SuppressWarnings("unchecked")
        final BeanIntrospection<T> introspection = (BeanIntrospection<T>) getBeanIntrospection(beanType);
        if (introspection == null) {
            throw new ValidationException("Passed bean type [" + beanType + "] cannot be introspected. Please annotate with @Introspected");
        }

        final Set<ConstraintViolation<T>> constraintViolations = validateConstructorParameters(introspection, arguments);

        if (constraintViolations.isEmpty()) {
            final T instance = introspection.instantiate(arguments);
            final Set<ConstraintViolation<T>> errors = validate(introspection, instance);
            if (errors.isEmpty()) {
                return instance;
            } else {
                throw new ConstraintViolationException(errors);
            }
        }

        throw new ConstraintViolationException(constraintViolations);
    }

    @Override
    public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
        return BeanIntrospector.SHARED.findIntrospection(clazz)
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
    public <T> Set<ConstraintViolation<T>> validateParameters(
            @NonNull T object,
            @NonNull ExecutableMethod method,
            @NonNull Object[] parameterValues,
            @Nullable Class<?>... groups
    ) {
        ArgumentUtils.requireNonNull("parameterValues", parameterValues);
        ArgumentUtils.requireNonNull("object", object);
        ArgumentUtils.requireNonNull("method", method);
        final Argument[] arguments = method.getArguments();
        final int argLen = arguments.length;
        if (argLen != parameterValues.length) {
            throw new IllegalArgumentException("The method parameter array must have exactly " + argLen + " elements.");
        }

        DefaultConstraintValidatorContext context = new DefaultConstraintValidatorContext(object, groups);
        Set overallViolations = new HashSet<>(5);

        final Path.Node node = context.addMethodNode(method);
        try {
            @SuppressWarnings("unchecked")
            final Class<T> rootClass = (Class<T>) object.getClass();
            validateParametersInternal(
                rootClass, object, parameterValues, arguments, argLen, context, overallViolations
            );
        } finally {
            context.removeLast();
        }
        //noinspection unchecked
        return Collections.unmodifiableSet(overallViolations);
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateParameters(
            @NonNull T object,
            @NonNull ExecutableMethod method,
            @NonNull Collection<MutableArgumentValue<?>> argumentValues,
            @Nullable Class<?>... groups
    ) {
        ArgumentUtils.requireNonNull("object", object);
        ArgumentUtils.requireNonNull("method", method);
        ArgumentUtils.requireNonNull("parameterValues", argumentValues);
        final Argument[] arguments = method.getArguments();
        final int argLen = arguments.length;
        if (argLen != argumentValues.size()) {
            throw new IllegalArgumentException("The method parameter array must have exactly " + argLen + " elements.");
        }

        DefaultConstraintValidatorContext context = new DefaultConstraintValidatorContext(object, groups);
        Set overallViolations = new HashSet<>(5);

        final Path.Node node = context.addMethodNode(method);
        try {
            @SuppressWarnings("unchecked")
            final Class<T> rootClass = (Class<T>) object.getClass();
            validateParametersInternal(rootClass, object,
                argumentValues.stream().map(ArgumentValue::getValue).toArray(), arguments, argLen,
                context, overallViolations);
        } finally {
            context.removeLast();
        }
        //noinspection unchecked
        return Collections.unmodifiableSet(overallViolations);
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateParameters(
            @NonNull T object,
            @NonNull Method method,
            @NonNull Object[] parameterValues,
            @Nullable Class<?>... groups
    ) {
        ArgumentUtils.requireNonNull("method", method);
        return executionHandleLocator.findExecutableMethod(
                method.getDeclaringClass(),
                method.getName(),
                method.getParameterTypes()
        ).map(executableMethod ->
                validateParameters(object, executableMethod, parameterValues, groups)
        ).orElse(Collections.emptySet());
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateReturnValue(
            @NonNull T object,
            @NonNull Method method,
            @Nullable Object returnValue,
            @Nullable Class<?>... groups
    ) {
        ArgumentUtils.requireNonNull("method", method);
        ArgumentUtils.requireNonNull("object", object);
        return executionHandleLocator.findExecutableMethod(
                method.getDeclaringClass(),
                method.getName(),
                method.getParameterTypes()
        ).map(executableMethod ->
                validateReturnValue(object, executableMethod, returnValue, groups)
        ).orElse(Collections.emptySet());
    }

    @Override
    public @NonNull <T> Set<ConstraintViolation<T>> validateReturnValue(
            @NonNull T object,
            @NonNull ExecutableMethod<?, Object> executableMethod,
            @Nullable Object returnValue,
            @Nullable Class<?>... groups
    ) {
        final ReturnType<Object> returnType = executableMethod.getReturnType();
        final HashSet overallViolations = new HashSet(3);
        @SuppressWarnings("unchecked")
        final Class<Object> rootClass = (Class<Object>) object.getClass();
        final DefaultConstraintValidatorContext context = new DefaultConstraintValidatorContext(object, groups);

        // The {BeanDefinitionInjectProcessor adds the Valid annotation to the method if return type has it}
        // In case of cascading @Valid to iterables - it might only be added to method
        // (as described in validateElement() method's NOTE)
        boolean hasValid = executableMethod.hasStereotype(Valid.class);
        boolean hasConstraint = executableMethod.hasStereotype(Constraint.class);

        Path.Node node = context.addReturnValueNode(returnType.asArgument().getName());
        validateElement(context, overallViolations, object, rootClass, returnType, object,
            returnType.getType(), returnValue, node, hasValid, hasConstraint, null);

        return overallViolations;
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(
            @NonNull Constructor<? extends T> constructor,
            @NonNull Object[] parameterValues,
            @Nullable Class<?>... groups) {
        ArgumentUtils.requireNonNull("constructor", constructor);
        final Class<? extends T> declaringClass = constructor.getDeclaringClass();
        final BeanIntrospection<? extends T> introspection = BeanIntrospection.getIntrospection(declaringClass);
        return validateConstructorParameters(introspection, parameterValues);
    }

    @Override
    @NonNull
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(
            @NonNull BeanIntrospection<? extends T> introspection,
            @NonNull Object[] parameterValues,
            @Nullable Class<?>... groups
    ) {
        ArgumentUtils.requireNonNull("introspection", introspection);
        final Class<? extends T> beanType = introspection.getBeanType();
        final Argument<?>[] constructorArguments = introspection.getConstructorArguments();
        return validateConstructorParameters(beanType, constructorArguments, parameterValues, groups);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(
        Class<? extends T> beanType,
        Argument<?>[] constructorArguments,
        @NonNull Object[] parameterValues,
        @Nullable Class<?>[] groups
    ) {
        //noinspection ConstantConditions
        parameterValues = parameterValues != null ? parameterValues : ArrayUtils.EMPTY_OBJECT_ARRAY;
        final int argLength = constructorArguments.length;
        if (parameterValues.length != argLength) {
            throw new IllegalArgumentException("Expected exactly [" + argLength + "] constructor arguments");
        }
        DefaultConstraintValidatorContext context = new DefaultConstraintValidatorContext(groups);
        Set overallViolations = new HashSet<>(5);

        final Path.Node node = context.addConstructorNode(beanType.getSimpleName(), constructorArguments);
        try {
            validateParametersInternal(
                    beanType,
                    null,
                    parameterValues,
                    constructorArguments,
                    argLength,
                    context,
                    overallViolations
            );
        } finally {
            context.removeLast();
        }
        //noinspection unchecked
        return Collections.unmodifiableSet(overallViolations);
    }

    @NonNull
    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorReturnValue(
        @NonNull Constructor<? extends T> constructor, @NonNull T createdObject,
        @Nullable Class<?>... groups
    ) {
        return validate(createdObject, groups);
    }

    @NonNull
    @Override
    public <T> Publisher<T> validatePublisher(
        @NonNull ReturnType returnType, @NonNull Publisher<T> publisher, Class<?>... groups
    ) {
        ArgumentUtils.requireNonNull("publisher", publisher);
        ArgumentUtils.requireNonNull("returnType", returnType);

        if (returnType.getTypeParameters().length == 0) {
            return publisher;
        }
        Argument<?> typeParameter = returnType.getTypeParameters()[0];

        Publisher<Object> output;
        if (Publishers.isSingle(returnType.getType())) {
            output = Mono.from(publisher).flatMap(value -> {
                Set violations = validatePublisherValue(returnType, publisher, groups, typeParameter, value);
                return violations.isEmpty() ? Mono.just(value) :
                    Mono.error(new ConstraintViolationException(violations));
            });
        } else {
            output = Flux.from(publisher).flatMap(value -> {
                Set violations = validatePublisherValue(returnType, publisher, groups, typeParameter, value);
                return violations.isEmpty() ? Flux.just(value) :
                    Flux.error(new ConstraintViolationException(violations));
            });
        }

        return Publishers.convertPublisher(output, ((ReturnType<Publisher>) returnType).getType());
    }

    /**
     * A method used inside the {@link #validatePublisher} method.
     */
    private <T> Set validatePublisherValue(
        ReturnType returnType, @NonNull Publisher<T> publisher, Class<?>[] groups,
        Argument<?> typeParameter, Object value
    ) {
        DefaultConstraintValidatorContext context =
            new DefaultConstraintValidatorContext(publisher, groups);
        context.addReturnValueNode(returnType.asArgument().getName());
        final Set<ConstraintViolation<Object>> violations = new HashSet<>();

        Class<?> publisherClass = publisher.getClass();
        validateIterableValue(context, violations, publisher,
            (Class<Object>) publisherClass, publisher, (Class<Object>) publisherClass,
            typeParameter, value, typeParameter.getType(), null, null, true);
        return violations;
    }

    @NonNull
    @Override
    public <T> CompletionStage<T> validateCompletionStage(
        @NonNull CompletionStage<T> completionStage, Class<?>... groups
    ) {
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
    public <T> void validateBeanArgument(
        @NonNull BeanResolutionContext resolutionContext,
        @NonNull InjectionPoint injectionPoint,
        @NonNull Argument<T> argument,
        int index,
        @Nullable T value
    ) throws BeanInstantiationException {
        final AnnotationMetadata annotationMetadata = argument.getAnnotationMetadata();
        final boolean hasValid = annotationMetadata.hasStereotype(Valid.class);
        final boolean hasConstraint = annotationMetadata.hasStereotype(Constraint.class);
        final Class rootClass = injectionPoint.getDeclaringBean().getBeanType();

        if (!hasConstraint && !hasValid) {
            return;
        }

        DefaultConstraintValidatorContext context = new DefaultConstraintValidatorContext(value);
        Set overallViolations = new HashSet<>(5);

        final Path.Node constructorNode = context.addConstructorNode(
            rootClass.getName(), injectionPoint.getDeclaringBean().getConstructor().getArguments());

        // Handle cascade validation annotation
        // create node, that will be removed inside validateElement()
        Path.Node node = context.addPropertyNode(argument.getName());
        validateElement(context, overallViolations, null, rootClass, argument,
            null, (Class<Object>) argument.getType(), value, node);

        // remove constructor node
        context.removeLast();

        failOnError(resolutionContext, overallViolations, rootClass);
    }

    @Override
    public <T> void validateBean(
        @NonNull BeanResolutionContext resolutionContext,
        @NonNull BeanDefinition<T> definition,
        @NonNull T bean
    ) throws BeanInstantiationException {
        final BeanIntrospection<T> introspection = (BeanIntrospection<T>) getBeanIntrospection(bean);
        if (introspection != null) {
            Set<ConstraintViolation<T>> errors = validate(introspection, bean);
            final Class<?> beanType = bean.getClass();
            failOnError(resolutionContext, errors, beanType);
        } else if (bean instanceof Intercepted && definition.hasStereotype(ConfigurationReader.class)) {
            final Collection<ExecutableMethod<T, ?>> executableMethods = definition.getExecutableMethods();
            if (CollectionUtils.isNotEmpty(executableMethods)) {
                Set<ConstraintViolation<T>> violations = new HashSet<>();
                final DefaultConstraintValidatorContext context = new DefaultConstraintValidatorContext(bean);
                final Class<T> beanType = definition.getBeanType();
                final Class<?>[] interfaces = beanType.getInterfaces();
                if (ArrayUtils.isNotEmpty(interfaces)) {
                    context.addConstructorNode(interfaces[0].getSimpleName());
                } else {
                    context.addConstructorNode(beanType.getSimpleName());
                }
                for (ExecutableMethod executableMethod : executableMethods) {
                    if (executableMethod.hasAnnotation(Property.class)) {
                        final boolean hasConstraint = executableMethod.hasStereotype(Constraint.class);
                        final boolean isValid = executableMethod.hasStereotype(Valid.class);
                        if (hasConstraint || isValid) {
                            final Object value = executableMethod.invoke(bean);
                            final ReturnType<Object> returnType = executableMethod.getReturnType();

                            Path.Node node = context.addPropertyNode(executableMethod.getName());
                            // create node, that will be removed inside validateElement()
                            validateElement(context, violations, bean, (Class<Object>) beanType,
                                returnType, bean, returnType.getType(), value, node);
                        }
                    }
                }

                failOnError(resolutionContext, violations, beanType);
            }
        }
    }

    /**
     * looks up a bean introspection for the given object by instance's class or defined class.
     *
     * @param object The object, never null
     * @param definedClass The defined class of the object, never null
     * @return The introspection or null
     */
    @SuppressWarnings({"WeakerAccess", "unchecked"})
    protected @Nullable BeanIntrospection<Object> getBeanIntrospection(
        @NonNull Object object, @NonNull Class<?> definedClass
    ) {
        //noinspection ConstantConditions
        if (object == null) {
            return null;
        }
        return BeanIntrospector.SHARED.findIntrospection((Class<Object>) object.getClass())
                .orElseGet(() -> BeanIntrospector.SHARED.findIntrospection((Class<Object>) definedClass).orElse(null));
    }

    /**
     * Looks up a bean introspection for the given object.
     *
     * @param object The object, never null
     * @return The introspection or null
     */
    @SuppressWarnings({"WeakerAccess", "unchecked"})
    protected @Nullable BeanIntrospection<Object> getBeanIntrospection(@NonNull Object object) {
        //noinspection ConstantConditions
        if (object == null) {
            return null;
        }
        if (object instanceof Class) {
            return BeanIntrospector.SHARED.findIntrospection((Class<Object>) object).orElse(null);
        }
        return BeanIntrospector.SHARED.findIntrospection((Class<Object>) object.getClass()).orElse(null);
    }

    /**
     * Processes a method argument that is a publisher. Since the argument cannot be validated
     * at this exact time, the publisher is exchanged for another publisher that performs validation
     * and publishes the original items.
     * The method can convert both single and reactive publishers.
     *
     * @param rootClass - class of the root object
     * @param rootObject - the root object
     * @param argumentValues the values of all the arguments
     * @param context validation context
     * @param argumentIndex the index of the publisher argument to be validated
     * @param argument argument corresponding to the parameter type
     * @param parameterType the type of the parameter
     * @param parameterValue the provided value of the argument
     */
    private <T> void instrumentPublisherArgumentWithValidation(
            @NonNull Class<T> rootClass,
            @Nullable T rootObject,
            @NonNull Object[] argumentValues,
            DefaultConstraintValidatorContext context,
            int argumentIndex,
            Argument argument,
            Class<?> parameterType,
            Object parameterValue
    ) {
        final Publisher<Object> publisher = Publishers.convertPublisher(parameterValue, Publisher.class);
        PathImpl copiedPath = new PathImpl(context.currentPath);

        if (Publishers.isSingle(parameterType)) {
            final Mono<Object> finalMono = Mono.from(publisher).flatMap(value -> {
                Set violations = validatePublisherElement(rootClass, rootObject, argumentIndex,
                    argument, parameterValue, value, copiedPath);
                return violations.isEmpty() ? Mono.just(value) :
                    Mono.error(new ConstraintViolationException(violations));
            });
            argumentValues[argumentIndex] = Publishers.convertPublisher(finalMono, parameterType);
        } else {
            final Flux<Object> finalFlux = Flux.from(publisher).flatMap(value -> {
                Set violations = validatePublisherElement(rootClass, rootObject, argumentIndex,
                    argument, parameterValue, value, copiedPath);
                return violations.isEmpty() ? Flux.just(value) :
                    Flux.error(new ConstraintViolationException(violations));
            });
            argumentValues[argumentIndex] = Publishers.convertPublisher(finalFlux, parameterType);
        }
    }

    /**
     * Method used inside the {@link #instrumentPublisherArgumentWithValidation}.
     */
    private <T> Set validatePublisherElement(
        @NonNull Class<T> rootClass,
        @Nullable T rootObject,
        int argumentIndex,
        Argument argument,
        Object parameterValue,
        Object publisherElement,
        PathImpl path
    ) {
        DefaultConstraintValidatorContext context =
            new DefaultConstraintValidatorContext(rootObject, path);
        Set violations = new HashSet();

        // noinspection unchecked
        Argument<Object>[] typeParameters = argument.getTypeParameters();

        if (typeParameters.length == 0) {
            // No validation if no parameters
            return violations;
        }
        Argument<Object> valueArgument = typeParameters[0];

        // Create the parameter node and the container element node
        context.addParameterNode(argument.getName(), argumentIndex);
        // noinspection unchecked
        Path.Node node = context.addContainerElementNode(valueArgument,
            (Class<Object>) parameterValue.getClass(), null, null, true);
        try {
            // node is removed from context inside validateElement()
            validateElement(context, violations, rootObject, (Class<Object>) rootClass,
                valueArgument, rootObject, valueArgument.getType(), publisherElement, node);
        } finally {
            context.removeLast();
        }

        return violations;
    }

    /**
     * Processes a method argument that is a completion stage. Since the argument cannot be validated
     * at this exact time, the validation is applied to the completion stage.
     */
    private <T> void instrumentCompletionStageArgumentWithValidation(
            @NonNull Class<T> rootClass,
            @Nullable T object,
            @NonNull Object[] argumentValues,
            DefaultConstraintValidatorContext context,
            int argumentIndex,
            Argument argument,
            Object parameterValue
    ) {
        final CompletionStage<Object> completionStage = (CompletionStage<Object>) parameterValue;
        PathImpl copiedPath = new PathImpl(context.currentPath);

        final CompletionStage<Object> validatedStage = completionStage.thenApply(value -> {
            DefaultConstraintValidatorContext newContext =
                new DefaultConstraintValidatorContext(object, copiedPath);
            Set newViolations = new HashSet();

            // noinspection unchecked
            Argument<Object>[] typeParameters = argument.getTypeParameters();

            if (typeParameters.length == 0) {
                // No validation if no parameters
                return value;
            }
            Argument<Object> valueArgument = typeParameters[0];

            // Create the parameter node and the container element node
            newContext.addParameterNode(argument.getName(), argumentIndex);
            // noinspection unchecked
            Path.Node node = newContext.addContainerElementNode(valueArgument,
                (Class<Object>) parameterValue.getClass(), null, null, true);
            try {
                // node is removed from context inside validateElement()
                validateElement(newContext, newViolations, object, (Class<Object>) rootClass, valueArgument, object,
                    valueArgument.getType(), value, node);
            } finally {
                newContext.removeLast();
            }

            if (!newViolations.isEmpty()) {
                throw new ConstraintViolationException(newViolations);
            }

            return value;
        });

        argumentValues[argumentIndex] = validatedStage;
    }

    @SuppressWarnings("unchecked")
    private <T> void validateParametersInternal(
            @NonNull Class<T> rootClass,
            @Nullable T object,
            @NonNull Object[] parameters,
            Argument[] arguments,
            int argLen,
            DefaultConstraintValidatorContext context,
            Set overallViolations
    ) {
        for (int parameterIndex = 0; parameterIndex < argLen; parameterIndex++) {
            Argument<?> argument = arguments[parameterIndex];
            final Class<?> parameterType = argument.getType();

            final AnnotationMetadata annotationMetadata = argument.getAnnotationMetadata();
            final boolean hasValid = annotationMetadata.hasStereotype(Validator.ANN_VALID) ||
                doesHaveValidatedTypeParameters(context, argument);
            final boolean hasConstraint = annotationMetadata.hasStereotype(Validator.ANN_CONSTRAINT);
            if (!hasValid && !hasConstraint) {
                continue;
            }

            Object parameterValue = parameters[parameterIndex];
            final boolean hasValue = parameterValue != null;

            final boolean isPublisher = hasValue && Publishers.isConvertibleToPublisher(parameterType);
            if (isPublisher) {
                instrumentPublisherArgumentWithValidation(
                    rootClass, object, parameters, context, parameterIndex, argument, parameterType, parameterValue);
                continue;
            }

            final boolean isCompletionStage = hasValue && CompletionStage.class.isAssignableFrom(parameterType);
            if (isCompletionStage) {
                instrumentCompletionStageArgumentWithValidation(
                    rootClass, object, parameters, context, parameterIndex, argument, parameterValue);
                continue;
            }

            // create node, that will be removed inside validateElement()
            Path.Node node = context.addParameterNode(argument.getName(), parameterIndex);
            //noinspection unchecked
            validateElement(context, overallViolations, object, (Class<Object>) rootClass, argument, object,
                (Class<Object>) argument.getType(), parameterValue, node,
                false, false, parameters);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void validatePojoInternal(
        @NonNull Class<T> rootClass,
        @Nullable T object,
        @Nullable Object[] argumentValues,
        @NonNull DefaultConstraintValidatorContext context,
        @NonNull Set overallViolations,
        @NonNull Class<?> parameterType,
        @NonNull Object parameterValue,
        Class<? extends Annotation> pojoConstraint,
        AnnotationValue constraintAnnotation
    ) {
        final ConstraintValidator constraintValidator = constraintValidatorRegistry
                .findConstraintValidator(pojoConstraint, parameterType).orElse(null);

        if (constraintValidator != null) {
            final String currentMessageTemplate = context.getMessageTemplate().orElse(null);
            if (!constraintValidator.isValid(parameterValue, constraintAnnotation, context)) {
                BeanIntrospection<Object> beanIntrospection = getBeanIntrospection(parameterValue);
                if (beanIntrospection == null) {
                    throw new ValidationException("Passed object [" + parameterValue + "] cannot be introspected. Please annotate with @Introspected");
                }
                AnnotationMetadata beanAnnotationMetadata = beanIntrospection.getAnnotationMetadata();
                AnnotationValue<? extends Annotation> annotationValue = beanAnnotationMetadata.getAnnotation(pojoConstraint);

                final String propertyValue = "";
                final String messageTemplate = buildMessageTemplate(context, annotationValue, beanAnnotationMetadata);
                final Map<String, Object> variables = newConstraintVariables(annotationValue, propertyValue, beanAnnotationMetadata);
                overallViolations.add(new DefaultConstraintViolation(
                        object,
                        rootClass,
                        object,
                        parameterValue,
                        messageSource.interpolate(messageTemplate, MessageSource.MessageContext.of(variables)),
                        messageTemplate,
                        new PathImpl(context.currentPath),
                        new DefaultConstraintDescriptor(beanAnnotationMetadata, pojoConstraint, annotationValue),
                        argumentValues));
            }
            context.messageTemplate(currentMessageTemplate);
        }
    }

    /**
     * Validates object's properties.
     *
     * @param introspection - object introspection
     * @param object - the object to validate
     * @param constrainedProperties - all the properties with constraints
     * @param cascadeProperties - all properties annotated with @Valid
     * @param pojoConstraints - pojo constraints
     * @return overallViolations
     */
    private <T> Set<ConstraintViolation<T>> doValidate(
        DefaultConstraintValidatorContext context, Set overallViolations,
        @NonNull T rootBean, BeanIntrospection<?> introspection, @NonNull Object object,
        Collection<? extends BeanProperty<Object, Object>> constrainedProperties,
        Collection<BeanProperty<Object, Object>> cascadeProperties,
        List<Class<? extends Annotation>> pojoConstraints
    ) {
        @SuppressWarnings("unchecked")
        final Class<Object> rootBeanClass = (Class<Object>) rootBean.getClass();

        for (BeanProperty<Object, Object> cascadeProperty: cascadeProperties) {
            final Object propertyValue = cascadeProperty.get(object);
            //noinspection unchecked
            final Class<Object> propertyType = propertyValue != null ?
                (Class<Object>) propertyValue.getClass() : cascadeProperty.getType();

            Path.Node node = context.addPropertyNode(cascadeProperty.getName());
            try {
                validateCascadeElement(
                    context, overallViolations, rootBean, rootBeanClass, cascadeProperty,
                    object, propertyType, propertyValue, null, node
                );
            } finally {
                context.removeLast();
            }
        }

        for (BeanProperty<Object, Object> constrainedProperty: constrainedProperties) {
            final Object propertyValue = constrainedProperty.get(object);
            context.addPropertyNode(constrainedProperty.getName());
            validateConstrainedElement(context, overallViolations, rootBean, rootBeanClass,
                object, constrainedProperty, constrainedProperty.getType(), propertyValue, null);
            context.removeLast();
        }

        for (Class<? extends Annotation> pojoConstraint : pojoConstraints) {
            // noinspection unchecked
            Class<Object> objectType = (Class<Object>) introspection.getBeanType();
            validatePojoInternal(rootBeanClass, rootBean, null, context, overallViolations,
                objectType, object, pojoConstraint, introspection.getAnnotation(pojoConstraint));
        }

        //noinspection unchecked
        return Collections.unmodifiableSet(overallViolations);
    }

    private <T> boolean doesRequireValidation(
        DefaultConstraintValidatorContext context, AnnotationMetadataProvider element
    ) {
        return element.getAnnotationMetadata().hasStereotype(Constraint.class) ||
            element.getAnnotationMetadata().hasStereotype(Valid.class) ||
            doesHaveValidatedTypeParameters(context, element);
    }

    private <T> boolean canCascade(
            Class<T> rootBeanClass,
            DefaultConstraintValidatorContext context,
            Object propertyValue,
            Path.Node node
    ) {
        final boolean isReachable = traversableResolver.isReachable(
            propertyValue,
            node,
            rootBeanClass,
            context.currentPath,
            ElementType.FIELD
        );
        if (!isReachable) {
            return false;
        }

        return traversableResolver.isCascadable(
            propertyValue,
            node,
            rootBeanClass,
            context.currentPath,
            ElementType.FIELD
        );
    }

    /**
     * Whether type parameters of a given element require validation.
     *
     * @param context the validation context
     * @param annotatedElement the element
     */
    private boolean doesHaveValidatedTypeParameters(
        DefaultConstraintValidatorContext context,
        AnnotationMetadataProvider annotatedElement
    ) {
        if (!context.elementRequireCascadeValidation.containsKey(annotatedElement)) {
            context.elementRequireCascadeValidation.put(annotatedElement, false);

            Argument<Object> annotatedElementAsArgument = null;
            if (annotatedElement instanceof Argument) {
                //noinspection unchecked
                annotatedElementAsArgument = (Argument<Object>) annotatedElement;
            } else if (annotatedElement instanceof ArgumentCoercible) {
                //noinspection unchecked
                annotatedElementAsArgument = ((ArgumentCoercible<Object>) annotatedElement).asArgument();
            }

            if (annotatedElementAsArgument != null) {
                Argument<?>[] arguments = annotatedElementAsArgument.getTypeParameters();

                for (Argument<?> argument: arguments) {
                    AnnotationMetadata metadata = argument.getAnnotationMetadata();
                    boolean hasValid = metadata.hasStereotype(Valid.class);
                    boolean hasConstraint = metadata.hasStereotype(Constraint.class);
                    if (hasValid || hasConstraint) {
                        context.elementRequireCascadeValidation.put(annotatedElement, true);
                    } else if (doesHaveValidatedTypeParameters(context, argument)) {
                        context.elementRequireCascadeValidation.put(annotatedElement, true);
                    }
                }
            }
        }

        return context.elementRequireCascadeValidation.get(annotatedElement);
    }

    /**
     * ValidatesElement on @Valid and Constraint annotations.
     * Works for properties, method arguments and return values.
     * For iterables validates iterable items with generic parameter annotations and iterables themselves.
     * NOTE: IntrospectedTypeVisitor adds @Valid on iterable if its arguments have any annotations.
     * NOTE: Removes the element node
     *
     * @param annotatedElement - element to validate - Argument, ReturnType, BeanProperty, etc.
     * @param annotatedElementType - the type of annotatedElement (not essentially value type)
     * @param elementValue - the value
     * @param hasValidCascade - if it has Valid that is cascaded from above (e.g. in ReturnType the annotations are on
     *                        the method itself and not on the ReturnType)
     * @param hasConstraintCascade - if it has Constraints that cascaded from above
     * @param executableParameterValues - the values of method arguments (in case annotated element is a paramter)
     */
    private void validateElement(
        DefaultConstraintValidatorContext context, Set overallViolations,
        Object rootBean, Class<Object> rootClass,
        AnnotationMetadataProvider annotatedElement, Object object,
        Class<Object> annotatedElementType, Object elementValue,
        @Nullable Path.Node elementNode, boolean hasValidCascade, boolean hasConstraintCascade,
        @Nullable Object[] executableParameterValues
    ) {
        //noinspection unchecked
        final Class<Object> elementType = elementValue != null ?
            (Class<Object>) elementValue.getClass() : annotatedElementType;

        AnnotationMetadata annotationMetadata = annotatedElement.getAnnotationMetadata();
        boolean hasValid = hasValidCascade || annotationMetadata.hasStereotype(Valid.class);
        boolean hasConstraint = hasConstraintCascade || annotationMetadata.hasStereotype(Constraint.class);
        boolean doesRequireCascadeValidation = doesHaveValidatedTypeParameters(context, annotatedElement);

        if (hasValid || doesRequireCascadeValidation) {
            try {
                validateCascadeElement(context, overallViolations, rootBean,
                        rootClass, annotatedElement, object, elementType,
                        elementValue, executableParameterValues, elementNode);
            } catch (Exception e) {
                if (elementNode != null) {
                    context.removeLast();
                }
                throw e;
            }
        }

        if (hasConstraint) {
            validateConstrainedElement(context, overallViolations, rootBean, rootClass, object,
                    annotatedElement, elementType, elementValue, executableParameterValues);
        }

        if (elementNode != null) {
            context.removeLast();
        }
    }

    private void validateElement(
        DefaultConstraintValidatorContext context, Set overallViolations,
        Object rootBean, Class<Object> rootClass,
        AnnotationMetadataProvider annotatedElement, Object object,
        Class<Object> annotatedElementType, Object elementValue, Path.Node elementNode
    ) {
        validateElement(context, overallViolations, rootBean, rootClass, annotatedElement, object,
            annotatedElementType, elementValue, elementNode, false, false, null);
    }

    /**
     * Validates element when it has @Valid annotation.
     * Checks if it is an iterable and then validates its arguments.
     * Otherwise cascades validation to the element
     * @param annotatedElement - the element to validate
     * @param elementType - the type of the element (this type will be used for getting value extractor in case of
     *                    iterable and introspection in case of a cascade validation)
     * @param elementValue - the value
     * @param executableParameterValues - the method argument values (in case annotated element is a parameter)
     * @param node - the node of this annotated element in the path
     */
    private void validateCascadeElement(
        DefaultConstraintValidatorContext context, Set overallViolations,
        Object rootBean, Class<Object> rootClass,
        AnnotationMetadataProvider annotatedElement, Object object,
        Class<Object> elementType, Object elementValue,
        @Nullable Object[] executableParameterValues,
        Path.Node node
    ) {
        boolean cascadedToIterable = false;
        // handle validation of iterables
        cascadedToIterable = validateIterable(context, overallViolations, rootBean,
            rootClass, object, annotatedElement, elementValue, elementType);

        // otherwise it needs cascading as a bean
        if (!cascadedToIterable && elementValue != null && !context.validatedObjects.contains(elementValue)) {
            final BeanIntrospection<Object> beanIntrospection = getBeanIntrospection(elementValue, elementType);

            if (beanIntrospection == null) {
                // Error if not introspected
                // noinspection unchecked
                overallViolations.add(createIntrospectionConstraintViolation(rootClass, object, context,
                    elementType, elementValue, executableParameterValues));
            } else {
                if (canCascade(rootClass, context, elementValue, node)) {
                    cascadeToObjectIntrospection(context, overallViolations, object,
                        elementValue, beanIntrospection);
                }
            }
        }
    }

    /**
     * Cascade to the values of iterable (object having a value extractor).
     * All the parameters match ones defined in validateCascadeElement(...) method.
     * @return - whether element was an iterable
     */
    private <T> boolean validateIterable(
            DefaultConstraintValidatorContext context, Set overallViolations,
            T rootBean, @NonNull Class<T> rootClass, Object object,
            @NonNull AnnotationMetadataProvider annotatedIterable, Object iterable, Class<Object> iterableType
    ) {
        // Check if it has valueExtractor
        final Optional<? extends ValueExtractor<Object>> opt = valueExtractorRegistry.findValueExtractor(iterableType);

        if (!opt.isPresent()) {
            return false;
        }
        if (iterable == null) {
            // Does not require validation (with @Valid annotation)
            return true;
        }

        // Get its type parameters
        ValueExtractor<Object> valueExtractor = opt.get();

        final Argument<Object> annotatedIterableAsArgument;
        if (annotatedIterable instanceof Argument) {
            //noinspection unchecked
            annotatedIterableAsArgument = (Argument<Object>) annotatedIterable;
        } else if (annotatedIterable instanceof ArgumentCoercible) {
            //noinspection unchecked
            annotatedIterableAsArgument = ((ArgumentCoercible<Object>) annotatedIterable).asArgument();
        } else {
            throw new UnsupportedOperationException("Only argument coercible types are supported");
        }
        Argument<?>[] arguments = annotatedIterableAsArgument.getTypeParameters();

        // Check if its values need validation
        final boolean keyValidation, valueValidation;
        if (arguments.length == 1) {
            // Iterable with one generic parameter
            keyValidation = false;
            valueValidation = doesRequireValidation(context, arguments[0]);
        } else if (arguments.length == 2)  {
            // Map has 2 parameters
            keyValidation = doesRequireValidation(context, arguments[0]);
            valueValidation = doesRequireValidation(context, arguments[1]);
        } else {
            // Filling the final values
            keyValidation = false;
            valueValidation = false;
        }

        if (!keyValidation && !valueValidation) {
            return true;
        }

        // extract and validate values
        valueExtractor.extractValues(iterable, new ValueExtractor.ValueReceiver() {
            @Override
            public void value(String nodeName, Object value) {
                validateIterableValue(context, overallViolations, rootBean, rootClass, object,
                        iterableType, arguments[0], value, arguments[0].getType(), null, null, false);
            }

            @Override
            public void iterableValue(String nodeName, Object iterableValue) {
                validateIterableValue(context, overallViolations, rootBean, rootClass, object,
                        iterableType, arguments[0], iterableValue, arguments[0].getType(), null, null, true);
            }

            @Override
            public void indexedValue(String nodeName, int i, Object iterableValue) {
                validateIterableValue(context, overallViolations, rootBean, rootClass, object,
                        iterableType, arguments[0], iterableValue, arguments[0].getType(), i, null, true);
            }

            @Override
            public void keyedValue(String nodeName, Object key, Object keyedValue) {
                if (keyValidation) {
                    validateIterableValue(context, overallViolations, rootBean, rootClass, object,
                            iterableType, arguments[0], key, arguments[0].getType(), null, key, true);
                }

                if (valueValidation) {
                    validateIterableValue(context, overallViolations, rootBean, rootClass, object,
                            iterableType, arguments[1], keyedValue, arguments[1].getType(), null, key, true);
                }
            }
        });

        return true;
    }

    /**
     * Cascades to an element of iterable.
     *
     * @param iterableType - the type of annotated iterable
     * @param valueArgument - the Argument representing iterable item
     */
    private <T> void validateIterableValue(
        DefaultConstraintValidatorContext context, Set overallViolations,
        @Nullable T rootBean, @NonNull Class<T> rootClass, Object object,
        Class<Object> iterableType, Argument<?> valueArgument,
        Object iterableValue, Class<?> iterableValueType,
        Integer index, Object key, boolean isInIterable
    ) {
        AnnotationMetadata metadata = valueArgument.getAnnotationMetadata();

        boolean hasValid = metadata.hasStereotype(Valid.class) ||
            doesHaveValidatedTypeParameters(context, valueArgument);
        boolean hasConstraint = metadata.hasStereotype(Constraint.class);

        if (!hasValid && !hasConstraint) {
            return;
        }

        Path.Node node = context.addContainerElementNode(valueArgument, iterableType, index, key, isInIterable);
        // noinspection unchecked
        Class<Object> valueType = iterableValue != null ?
            (Class<Object>) iterableValue.getClass() : (Class<Object>) iterableValueType;

        if (hasValid) {
            try {
                // noinspection unchecked
                validateCascadeElement(context, overallViolations, rootBean, (Class<Object>) rootClass,
                    valueArgument, object, valueType, iterableValue, null, node);
            } catch (Exception e) {
                context.removeLast();
                throw e;
            }
        }

        if (hasConstraint) {
            validateConstrainedElement(context, overallViolations, rootBean, rootClass, object,
                valueArgument, valueType, iterableValue, null);
        }

        context.removeLast();
    }

    /**
     * Validates the given object (all its properties) with its introspection.
     *
     * @param object - the object to validate
     * @param beanIntrospection - its introspection
     */
    private <T> void cascadeToObjectIntrospection(
        @NonNull DefaultConstraintValidatorContext context, @NonNull Set overallViolations,
        T rootBean, @NonNull Object object, @NonNull BeanIntrospection<Object> beanIntrospection
    ) {
        context.validatedObjects.add(object);

        final Collection<BeanProperty<Object, Object>> cascadeConstraints =
                beanIntrospection.getIndexedProperties(Constraint.class);
        final Collection<BeanProperty<Object, Object>> cascadeNestedProperties =
                beanIntrospection.getBeanProperties().stream()
                    .filter(p -> p.hasStereotype(Valid.class) ||
                        doesHaveValidatedTypeParameters(context, p))
                    .collect(Collectors.toList());
        final List<Class<? extends Annotation>> pojoConstraints =
            beanIntrospection.getAnnotationMetadata().getAnnotationTypesByStereotype(Constraint.class);

        if (CollectionUtils.isNotEmpty(cascadeConstraints) ||
            CollectionUtils.isNotEmpty(cascadeNestedProperties) ||
            CollectionUtils.isNotEmpty(pojoConstraints)
        ) {
            doValidate(context, overallViolations, rootBean, beanIntrospection, object,
                cascadeConstraints, cascadeNestedProperties, pojoConstraints);
        }
    }

    /**
     * Validates the constraints on the given value.
     *
     * @param object - the object that this element belongs to (like object of property)
     * @param annotatedElement - the element to get the annotations from
     * @param elementType - the type of the value
     * @param elementValue - the value to validate constraints
     * @param executableParameterValues - parameter values of method (if annotated element is a paramter)
     */
    private <T> void validateConstrainedElement(
        DefaultConstraintValidatorContext context, Set overallViolations,
        @Nullable T rootBean, @NonNull Class<T> rootBeanClass,
        @Nullable Object object, @NonNull AnnotationMetadataProvider annotatedElement,
        @NonNull Class<?> elementType, @Nullable Object elementValue, @Nullable Object[] executableParameterValues
    ) {
        final AnnotationMetadata annotationMetadata = annotatedElement.getAnnotationMetadata();
        final List<Class<? extends Annotation>> constraintTypes =
            annotationMetadata.getAnnotationTypesByStereotype(Constraint.class);

        final String currentMessageTemplate = context.getMessageTemplate().orElse(null);

        for (Class<? extends Annotation> constraintType : constraintTypes) {
            valueConstraintOnElement(context, overallViolations, rootBean, rootBeanClass, object, annotatedElement,
                elementType, elementValue, executableParameterValues, constraintType);
        }
        context.messageTemplate(currentMessageTemplate);
    }

    @SuppressWarnings("unchecked")
    private <T> void valueConstraintOnElement(
        DefaultConstraintValidatorContext context, Set overallViolations,
        @Nullable T rootBean, @Nullable Class<T> rootBeanClass, @Nullable Object object,
        AnnotationMetadataProvider constrainedElement, Class elementType, @Nullable Object elementValue,
        @Nullable Object[] executableParameterValues,
        Class<? extends Annotation> constraintType
    ) {
        final AnnotationMetadata annotationMetadata = constrainedElement.getAnnotationMetadata();
        final List<? extends AnnotationValue<? extends Annotation>> annotationValues = annotationMetadata
                .getAnnotationValuesByType(constraintType);

        Set<AnnotationValue<? extends Annotation>> constraints = new HashSet<>(3);
        boolean isDefaultGroup = context.groups == DEFAULT_GROUPS ||
            context.groups.contains(Default.class);
        for (AnnotationValue<? extends Annotation> annotationValue : annotationValues) {
            final Class<?>[] classValues = annotationValue.classValues("groups");
            if (isDefaultGroup && ArrayUtils.isEmpty(classValues)) {
                constraints.add(annotationValue);
            } else {
                final List<Class> constraintGroups = Arrays.asList(classValues);
                if (context.groups.stream().anyMatch(group -> constraintGroups.contains(group))) {
                    constraints.add(annotationValue);
                }
            }
        }

        @SuppressWarnings("unchecked")
        final Class<Object> targetType = elementValue != null ? (Class<Object>) elementValue.getClass() : elementType;
        final ConstraintValidator<? extends Annotation, Object> validator = constraintValidatorRegistry
                .findConstraintValidator(constraintType, targetType).orElse(null);
        if (validator != null) {
            for (AnnotationValue annotationValue : constraints) {
                //noinspection unchecked
                if (!validator.isValid(elementValue, annotationValue, context)) {
                    final String messageTemplate = buildMessageTemplate(context, annotationValue, annotationMetadata);
                    final Map<String, Object> variables = newConstraintVariables(annotationValue, elementValue, annotationMetadata);
                    final String message = messageSource.interpolate(messageTemplate, MessageSource.MessageContext.of(variables));
                    final ConstraintDescriptor<?> constraintDescriptor =
                        new DefaultConstraintDescriptor(annotationMetadata, constraintType, annotationValue);

                    //noinspection unchecked
                    overallViolations.add(
                        new DefaultConstraintViolation(
                            rootBean, rootBeanClass, object, elementValue,
                            message, messageTemplate, new PathImpl(context.currentPath), constraintDescriptor,
                            executableParameterValues
                        )
                    );
                }
            }
        }
    }

    private Map<String, Object> newConstraintVariables(
            AnnotationValue annotationValue, @Nullable Object propertyValue,
            AnnotationMetadata annotationMetadata
    ) {
        final Map<?, ?> values = annotationValue.getValues();
        int initSize = (int) Math.ceil(values.size() / 0.75);
        Map<String, Object> variables = new LinkedHashMap<>(initSize);
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            variables.put(entry.getKey().toString(),  entry.getValue());
        }
        variables.put("validatedValue", propertyValue);
        final Map<String, Object> defaultValues = annotationMetadata.getDefaultValues(annotationValue.getAnnotationName());
        for (Map.Entry<String, Object> entry : defaultValues.entrySet()) {
            final String n = entry.getKey();
            if (!variables.containsKey(n)) {
                final Object v = entry.getValue();
                if (v != null) {
                    variables.put(n, v);
                }
            }
        }
        return variables;
    }

    private String buildMessageTemplate(final DefaultConstraintValidatorContext context, final AnnotationValue<?> annotationValue,
                                        final AnnotationMetadata annotationMetadata) {
        return context.getMessageTemplate()
            .orElseGet(() -> annotationValue.stringValue("message")
                .orElseGet(() -> annotationMetadata.getDefaultValue(annotationValue.getAnnotationName(), "message", String.class)
                            .orElse("{" + annotationValue.getAnnotationName() + ".message}")));
    }

    private <T> void failOnError(
        @NonNull BeanResolutionContext resolutionContext,
        Set<ConstraintViolation<T>> errors, Class<?> beanType
    ) {
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
    private <T> DefaultConstraintViolation<T> createIntrospectionConstraintViolation(
        @NonNull Class<T> rootClass,
        T object,
        DefaultConstraintValidatorContext context,
        Class<?> parameterType,
        Object parameterValue,
        Object... parameters
    ) {
        final String messageTemplate = context.getMessageTemplate()
            .orElseGet(() -> "{" + Introspected.class.getName() + ".message}");
        return new DefaultConstraintViolation<>(object, rootClass, object, parameterValue,
            messageSource.interpolate(messageTemplate, MessageSource.MessageContext.of(Collections.singletonMap("type", parameterType.getName()))),
            messageTemplate, new PathImpl(context.currentPath), null, parameters);
    }

    /**
     * The context object.
     */
    private final class DefaultConstraintValidatorContext implements ConstraintValidatorContext {
        final Map<AnnotationMetadataProvider, Boolean> elementRequireCascadeValidation = new HashMap<>(1);
        final Set<Object> validatedObjects = new HashSet<>(20);
        final PathImpl currentPath;
        final List<Class<?>> groups;
        String messageTemplate = null;

        private <T> DefaultConstraintValidatorContext(T object, Class<?>... groups) {
            this(object, new PathImpl(), groups);
        }

        private <T> DefaultConstraintValidatorContext(T object, PathImpl path, Class<?>... groups) {
            if (object != null) {
                validatedObjects.add(object);
            }
            if (ArrayUtils.isNotEmpty(groups)) {
                sanityCheckGroups(groups);

                List<Class<?>> groupList = new ArrayList<>();
                for (Class<?> group: groups) {
                    addInheritedGroups(group, groupList);
                }
                this.groups = Collections.unmodifiableList(groupList);
            } else {
                this.groups = DEFAULT_GROUPS;
            }

            this.currentPath = path != null ? path : new PathImpl();
        }

        private DefaultConstraintValidatorContext(Class<?>... groups) {
            this(null, groups);
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

        @Nullable
        @Override
        public Object getRootBean() {
            return validatedObjects.isEmpty() ? null : validatedObjects.iterator().next();
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

        Path.Node addReturnValueNode(String name) {
            final DefaultReturnValueNode returnValueNode = new DefaultReturnValueNode(name);
            currentPath.nodes.add(returnValueNode);
            return returnValueNode;
        }

        Path.Node addContainerElementNode(Argument<?> elementArgument, Class<Object> containerClass,
                                          Integer index, Object key, boolean isInIterable) {
            final DefaultContainerElementNode node = new DefaultContainerElementNode(
                elementArgument, containerClass, index, key, isInIterable);
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
            final DefaultConstructorNode node = new DefaultConstructorNode(new MethodReference<Object, Object>() {

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

                    if (node.getName() != null) {
                        builder.append('<').append(node.getName()).append('>');
                    }
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
    private static final class DefaultContainerElementNode
        extends DefaultNode implements Path.ContainerElementNode {
        private final Class<?> containerClass;
        private final Integer index;
        private final Object key;
        private final boolean isInIterable;

        public DefaultContainerElementNode(
            @Nullable String name, Class<?> containerClass,
            @Nullable Integer index, @Nullable Object key, boolean isInIterable
        ) {
            super(name);
            this.containerClass = containerClass;
            this.index = index;
            this.key = key;
            this.isInIterable = isInIterable;
        }

        public DefaultContainerElementNode(
            @Nullable Argument<?> elementArgument, Class<?> containerClass,
            @Nullable Integer index, @Nullable Object key, boolean isInIterable
        ) {
            this(createName(elementArgument), containerClass, index, key, isInIterable);
        }

        private static String createName(@Nullable Argument<?> elementArgument) {
            return elementArgument == null ? null :
                elementArgument.getName() + " " + elementArgument.getType().getSimpleName();
        }

        @Override
        public Class<?> getContainerClass() {
            return containerClass;
        }

        @Override
        public Integer getTypeArgumentIndex() {
            return null;
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
     * @param rootBean the root bean given for validation
     * @param rootBeanClass the type of the root bean
     * @param leafBean the bean that failed validation
     * @param invalidValue the value of the leaf bean
     * @param message message
     * @param messageTemplate the template used for message
     * @param path the path to the leaf bean
     * @param constraintDescriptor the descriptor of constraint for which validation failed
     * @param executableParameterValues the arguments provided to method if executable was validated
     * @param <T> The bean type.
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
