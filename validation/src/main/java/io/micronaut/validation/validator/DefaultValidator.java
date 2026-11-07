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

import io.micronaut.context.BeanResolutionContext;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.exceptions.BeanInstantiationException;
import io.micronaut.core.annotation.AnnotatedElement;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableArgumentValue;
import io.micronaut.core.type.ReturnType;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.InjectionPoint;
import io.micronaut.inject.MethodReference;
import io.micronaut.inject.annotation.AnnotatedElementValidator;
import io.micronaut.inject.validation.BeanDefinitionValidator;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ValidationException;
import jakarta.validation.metadata.BeanDescriptor;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import static io.micronaut.validation.ConstraintViolationExceptionUtil.createConstraintViolationException;
import static io.micronaut.validation.validator.ValidationChecks.requireNonEmpty;
import static io.micronaut.validation.validator.ValidationChecks.requireNonNull;

/**
 * Default implementation of the {@link Validator} interface.
 *
 * @author graemerocher
 * @author Andriy Dmytruk
 * @since 1.2
 */
@Singleton
@Primary
public class DefaultValidator
        implements Validator,
                ExecutableMethodValidator,
                ReactiveValidator,
                AnnotatedElementValidator,
                BeanDefinitionValidator,
                AutoCloseable {

    private final ReflectionSupport reflectionSupport;
    private final ClockProvider clockProvider;
    private final MessageInterpolator messageInterpolator;
    private final ConversionService conversionService;
    private final ValidatorDeclarations declarations;
    private final BeanIntrospector beanIntrospector;
    private final ConstraintValidatorOverrides validatorOverrides;
    private final ConstraintValidatorInstances validatorInstances;
    private final boolean isPrependPropertyPath;
    private final ConstraintEvaluator constraints;
    private final ValidationTraversal traversal;
    private final DefaultExecutableValidator executables;
    private final DefaultBeanDefinitionValidator beanDefinitions;

    /**
     * Default constructor.
     *
     * @param configuration The validator configuration
     */
    public DefaultValidator(ValidatorConfiguration configuration) {
        requireNonNull("configuration", configuration);
        // the support decides which introspections of the configured introspector the validator reads: without
        // the reflection module, the generated ones only
        ReflectionSupport configuredSupport = configuration.getReflectionSupport();
        BeanIntrospector configuredIntrospector = configuration.getBeanIntrospector();
        this.reflectionSupport = configuredSupport.introspector() == configuredIntrospector
            ? configuredSupport : configuredSupport.withIntrospector(configuredIntrospector);
        this.clockProvider = configuration.getClockProvider();
        this.messageInterpolator = configuration.getMessageInterpolator();
        this.conversionService = configuration.getConversionService();
        this.beanIntrospector = reflectionSupport.introspector();
        this.validatorOverrides = configuration.getConstraintValidatorOverrides();
        this.validatorInstances = new ConstraintValidatorInstances(configuration.getInternalConstraintValidatorFactory(), beanIntrospector);
        this.isPrependPropertyPath = configuration.isPrependPropertyPath();
        this.declarations = new ValidatorDeclarations(beanIntrospector, configuration.isStrictConstraintDefinitions(), reflectionSupport,
            configuration.getParameterNameProvider());
        this.constraints = new ConstraintEvaluator(reflectionSupport, configuration.getConstraintValidatorRegistry(), validatorOverrides,
            validatorInstances, declarations, messageInterpolator);
        this.traversal = new ValidationTraversal(this, reflectionSupport, configuration.getValueExtractorRegistry(),
            configuration.getTraversableResolver(), declarations, constraints);
        this.executables = new DefaultExecutableValidator(this, reflectionSupport, configuration.getExecutionHandleLocator(),
            conversionService, declarations, traversal, constraints);
        this.beanDefinitions = new DefaultBeanDefinitionValidator(this, reflectionSupport, traversal);
    }

    /**
     * Releases initialized validators and cached metadata exactly once.
     *
     * @since 5.3.0
     */
    @Override
    @PreDestroy
    public void close() {
        checkCloseAllowed();
        try {
            validatorInstances.close();
        } finally {
            constraints.clear();
            traversal.clear();
            declarations.clear();
        }
    }

    /** The initialized constraint validators, owned by the factory for a validator of a context. */
    final ConstraintValidatorInstances validatorInstances() {
        return validatorInstances;
    }

    /** Rejects closure from a validator callback before factory state changes. */
    final void checkCloseAllowed() {
        validatorInstances.checkCloseAllowed();
    }

    final void checkOpen() {
        validatorInstances.checkOpen();
    }

    final ReflectionSupport reflectionSupport() {
        return reflectionSupport;
    }

    final ValidatorDeclarations declarations() {
        return declarations;
    }

    final ConversionService conversionService() {
        return conversionService;
    }

    final MessageInterpolator messageInterpolator() {
        return messageInterpolator;
    }

    final ValidationTraversal traversal() {
        return traversal;
    }

    final <T> ConstraintViolationException constraintViolationException(Set<ConstraintViolation<T>> violations) {
        return createConstraintViolationException(isPrependPropertyPath, violations);
    }

    final String parameterName(MethodReference<?, ?> method, int index) {
        return executables.parameterName(method, index);
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

    @Override
    public <T> Set<ConstraintViolation<T>> validate(T object, @Nullable Class<?>... groups) {
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
    public <T> Set<ConstraintViolation<T>> validate(BeanIntrospection<T> introspection, T object, Class<?>... groups) {
        return validate(introspection, object, BeanValidationContext.fromGroups(groups));
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validate(BeanIntrospection<T> introspection, T object, BeanValidationContext context) {
        if (introspection == null) {
            throw new ValidationException("Passed object [" + object + "] cannot be introspected. Please annotate with @Introspected");
        }
        DefaultConstraintValidatorContext<T> constraintValidatorContext = new DefaultConstraintValidatorContext<>(this, introspection, object, context);
        traversal.doValidate(constraintValidatorContext, introspection, object);
        return constraintValidatorContext.getOverallViolations();
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName, Class<?>... groups) {
        return validateProperty(object, propertyName, BeanValidationContext.fromGroups(groups));
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName, @Nullable BeanValidationContext context) {
        requireNonNull("object", object);
        requireNonEmpty("propertyName", propertyName);
        context = context != null ? context : BeanValidationContext.DEFAULT;
        final BeanIntrospection<T> introspection = getBeanIntrospection(object);
        if (introspection == null) {
            throw new ValidationException("Passed object [" + object + "] cannot be introspected. Please annotate with @Introspected");
        }

        final Optional<BeanProperty<T, Object>> property = introspection.getProperty(propertyName);
        if (property.isEmpty()) {
            throw new IllegalArgumentException("Cannot find property with name: " + propertyName);
        }

        DefaultConstraintValidatorContext<T> constraintValidationContext = new DefaultConstraintValidatorContext<>(this, introspection, object, context);
        for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : constraintValidationContext.findGroupSequences(introspection)) {
            try (DefaultConstraintValidatorContext.GroupsValidation validation = constraintValidationContext.withGroupSequence(groupSequence)) {
                traversal.visitProperty(constraintValidationContext, introspection, object, property.get(), false);
                if (validation.isFailed()) {
                    break;
                }
            }
        }
        return Collections.unmodifiableSet(constraintValidationContext.getOverallViolations());
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateValue(Class<T> beanType, String propertyName, @Nullable Object value, Class<?>... groups) {
        requireNonNull("groups", groups);
        return validateValue(beanType, propertyName, value, BeanValidationContext.fromGroups(groups));
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateValue(Class<T> beanType, String propertyName, @Nullable Object value, BeanValidationContext context) {
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
            if (traversal.isNotReachable(constraintContext, null)) {
                return Collections.unmodifiableSet(constraintContext.getOverallViolations());
            }
            for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : constraintContext.findGroupSequences(introspection)) {
                try (DefaultConstraintValidatorContext.GroupsValidation validation = constraintContext.withGroupSequence(groupSequence)) {
                    AnnotationMetadata annotationMetadata = beanProperty.getAnnotationMetadata();
                    Argument<Object> propertyArgument = beanProperty.asArgument();
                    propertyArgument = Argument.of(propertyArgument.getType(), annotationMetadata, propertyArgument.getTypeParameters());
                    traversal.visitElement(constraintContext, null, propertyArgument, value, false);
                    if (validation.isFailed()) {
                        break;
                    }
                }
            }
        }
        return Collections.unmodifiableSet(constraintContext.getOverallViolations());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> validatedAnnotatedElement(AnnotatedElement element, @Nullable Object value) {
        requireNonNull("element", element);
        if (!ConstraintContainers.hasConstraints(reflectionSupport, element.getAnnotationMetadata(), reflectionSupport.classLoader())) {
            return Collections.emptySet();
        }

        final DefaultConstraintValidatorContext<Object> context = new DefaultConstraintValidatorContext<>(this, null, value, BeanValidationContext.DEFAULT);

        Argument<Object> type = value != null ? Argument.of((Class<Object>) value.getClass(), element.getAnnotationMetadata()) : Argument.OBJECT_ARGUMENT;
        boolean canCascade = true;
        try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addPropertyNode(element.getName())) {
            for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : context.findGroupSequences()) {
                try (DefaultConstraintValidatorContext.GroupsValidation validation = context.withGroupSequence(groupSequence)) {
                    traversal.visitElement(context, element, type, value, canCascade);
                    if (validation.isFailed()) {
                        break;
                    }
                }
                canCascade = false;
            }
        }
        List<String> messages = new ArrayList<>(context.getOverallViolations().size());
        for (ConstraintViolation<Object> violation : context.getOverallViolations()) {
            messages.add(violation.getMessage());
        }
        return Set.copyOf(messages);
    }

    @Override
    public <T> T createValid(Class<T> beanType, Object... arguments) throws ConstraintViolationException {
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
        checkOpen();
        if (clazz == null) {
            throw new IllegalArgumentException();
        }
        return beanIntrospector.findIntrospection(clazz)
            .map(introspection -> (BeanDescriptor) new IntrospectedBeanDescriptor(introspection, validatorOverrides, declarations))
            .orElseGet(() -> new EmptyDescriptor(clazz));
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        if (type != null && type.isInstance(this)) {
            return type.cast(this);
        }
        throw new ValidationException("Validator unwrapping to " + type + " not supported by this implementation");
    }

    @Override
    public ExecutableMethodValidator forExecutables() {
        checkOpen();
        return this;
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateParameters(T object, ExecutableMethod method, Object[] parameterValues, Class<?>... groups) {
        requireNonNull("groups", groups);
        return validateParameters(object, method, parameterValues, BeanValidationContext.fromGroups(groups));
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateParameters(T object, ExecutableMethod method, Object[] parameterValues, @Nullable BeanValidationContext validationContext) {
        return executables.validateParameters(object, method, parameterValues, validationContext);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateParameters(T object, ExecutableMethod method, Collection<MutableArgumentValue<?>> argumentValues, Class<?>... groups) {
        return executables.validateParameters(object, method, argumentValues, groups);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateParameters(T object, Method method, Object[] parameterValues, Class<?>... groups) {
        requireNonNull("method", method);
        requireNonNull("groups", groups);
        return validateParameters(object, executables.executableMethod(object, method), parameterValues, groups);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateReturnValue(T object, Method method, @Nullable Object returnValue, Class<?>... groups) {
        requireNonNull("method", method);
        requireNonNull("object", object);
        requireNonNull("groups", groups);
        return validateReturnValue(object, executables.executableMethod(object, method), returnValue, groups);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateReturnValue(T bean, ExecutableMethod<?, Object> executableMethod, @Nullable Object returnValue, Class<?>... groups) {
        requireNonNull("groups", groups);
        return validateReturnValue(bean, executableMethod, returnValue, BeanValidationContext.fromGroups(groups));
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateReturnValue(T bean, ExecutableMethod<?, Object> executableMethod, @Nullable Object returnValue, @Nullable BeanValidationContext validationContext) {
        return executables.validateReturnValue(bean, executableMethod, returnValue, validationContext);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(Constructor<? extends T> constructor, Object[] parameterValues, Class<?>... groups) {
        return executables.validateConstructorParameters(constructor, parameterValues, groups);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(BeanIntrospection<? extends T> introspection, Object[] parameterValues, Class<?>... groups) {
        return executables.validateConstructorParameters(introspection, parameterValues, groups);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(Class<? extends T> beanType, Argument<?>[] constructorArguments, Object[] parameterValues, Class<?>[] groups) {
        requireNonNull("groups", groups);
        return validateConstructorParameters(beanType, constructorArguments, parameterValues, BeanValidationContext.fromGroups(groups));
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(Class<? extends T> beanType, Argument<?>[] constructorArguments, Object[] parameterValues, @Nullable BeanValidationContext validationContext) {
        return executables.validateConstructorParameters(beanType, null, AnnotationMetadata.EMPTY_METADATA, constructorArguments, parameterValues,
            requireNonNull("validationContext", validationContext), null);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorReturnValue(Constructor<? extends T> constructor, T createdObject, Class<?>... groups) {
        return executables.validateConstructorReturnValue(constructor, createdObject, groups);
    }

    @Override
    public <T> Publisher<Object> validateReturnedPublisher(T bean, ExecutableMethod<?, Object> executableMethod, Publisher<Object> publisher, Class<?>... groups) {
        return executables.validateReturnedPublisher(bean, executableMethod, publisher, groups);
    }

    @Override
    public <T> CompletionStage<Object> validateReturnedCompletionStage(T bean, ExecutableMethod<?, Object> executableMethod, CompletionStage<Object> completionStage, Class<?>... groups) {
        return executables.validateReturnedCompletionStage(bean, executableMethod, completionStage, groups);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateReturnedContainer(T bean, ExecutableMethod<?, Object> executableMethod, @Nullable Object container, Class<?>... groups) {
        return executables.validateReturnedContainer(bean, executableMethod, container, groups);
    }

    @Override
    public <T> Publisher<T> validatePublisher(ReturnType<?> returnType, Publisher<T> publisher, Class<?>... groups) {
        return executables.validatePublisher(returnType, publisher, groups);
    }

    @Override
    public <T> CompletionStage<T> validateCompletionStage(CompletionStage<T> completionStage, Argument<T> argument, Class<?>... groups) {
        return executables.validateCompletionStage(completionStage, argument, groups);
    }

    @Override
    public <T> void validateBeanArgument(BeanResolutionContext resolutionContext,
                                         InjectionPoint injectionPoint,
                                         Argument<T> argument,
                                         int index,
                                         @Nullable T value) throws BeanInstantiationException {
        beanDefinitions.validateBeanArgument(resolutionContext, injectionPoint, argument, value);
    }

    @Override
    public <T> void validateBean(BeanResolutionContext resolutionContext, BeanDefinition<T> definition, T bean) throws BeanInstantiationException {
        beanDefinitions.validateBean(resolutionContext, definition, bean);
    }

    /**
     * Looks up a bean introspection for the given object by instance's class or defined class.
     *
     * @param object The object, never null
     * @param definedClass The defined class of the object, never null
     * @param <T> The introspection type
     * @return The introspection or null
     */
    @SuppressWarnings({"WeakerAccess", "unchecked"})
    protected <T> @Nullable BeanIntrospection<T> getBeanIntrospection(T object, Class<T> definedClass) {
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
     * @param <T> The introspection type
     * @return The introspection or null
     */
    @SuppressWarnings({"WeakerAccess", "unchecked"})
    protected <T> @Nullable BeanIntrospection<T> getBeanIntrospection(T object) {
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
     * @param <T> The introspection type
     * @return The introspection or null
     */
    @SuppressWarnings("WeakerAccess")
    protected <T> @Nullable BeanIntrospection<T> getBeanIntrospection(Class<T> type) {
        return beanIntrospector.findIntrospection(type).orElse(null);
    }
}
