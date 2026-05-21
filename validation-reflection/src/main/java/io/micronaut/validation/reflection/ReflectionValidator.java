/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.validation.reflection;

import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.validation.validator.BeanValidationContext;
import io.micronaut.validation.validator.DefaultValidator;
import io.micronaut.validation.validator.ValidatorConfiguration;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.InternalConstraintValidatorFactory;
import io.micronaut.validation.validator.extractors.ValueExtractorDefinition;
import io.micronaut.validation.validator.extractors.ValueExtractorRegistry;
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ClockProvider;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.GroupSequence;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.OverridesAttribute;
import jakarta.validation.Payload;
import jakarta.validation.Path;
import jakarta.validation.UnexpectedTypeException;
import jakarta.validation.Valid;
import jakarta.validation.TraversableResolver;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstructorDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.CrossParameterDescriptor;
import jakarta.validation.metadata.ElementDescriptor;
import jakarta.validation.metadata.GroupConversionDescriptor;
import jakarta.validation.metadata.MethodDescriptor;
import jakarta.validation.metadata.MethodType;
import jakarta.validation.metadata.ParameterDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;
import jakarta.validation.metadata.ReturnValueDescriptor;
import jakarta.validation.metadata.Scope;
import jakarta.validation.metadata.ValidateUnwrappedValue;
import jakarta.validation.valueextraction.Unwrapping;
import jakarta.validation.valueextraction.ValueExtractor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Internal opt-in reflection fallback validator used by the Jakarta Validation
 * compliance stack.
 *
 * @since 5.1
 */
@Internal
@Singleton
@Primary
@Replaces(DefaultValidator.class)
@Requires(property = ReflectionValidator.ENABLED, notEquals = StringUtils.FALSE, defaultValue = StringUtils.TRUE)
public final class ReflectionValidator extends DefaultValidator {

    /**
     * Enables reflection fallback validation.
     */
    public static final String ENABLED = "micronaut.validator.spec.reflection.enabled";

    /**
     * Enables one-time reflection fallback warnings.
     */
    public static final String WARNINGS_ENABLED = "micronaut.validator.spec.reflection-warnings.enabled";

    private static final Logger LOG = LoggerFactory.getLogger(ReflectionValidator.class);
    private static final ConcurrentMap<String, Boolean> WARNED_REFLECTION_ACCESS = new ConcurrentHashMap<>();
    private static final String ARGUMENT_OBJECT = "object";
    private static final String ARGUMENT_GROUPS = "groups";
    private static final String MEMBER_GROUPS = "groups";
    private static final String MEMBER_VALIDATION_APPLIES_TO = "validationAppliesTo";
    private static final String MEMBER_VALUE = "value";

    private final ValidatorConfiguration configuration;
    private final MessageInterpolator reflectionMessageInterpolator;
    private final ClockProvider reflectionClockProvider;
    private final TraversableResolver reflectionTraversableResolver;
    private final ValueExtractorRegistry reflectionValueExtractorRegistry;
    private final boolean warningsEnabled;

    /**
     * Creates a reflection fallback validator with warnings enabled.
     *
     * @param configuration The validator configuration
     */
    public ReflectionValidator(ValidatorConfiguration configuration) {
        this(configuration, true);
    }

    /**
     * Creates a reflection fallback validator.
     *
     * @param configuration The validator configuration
     * @param warningsEnabled Whether reflection warnings are enabled
     */
    @Inject
    public ReflectionValidator(ValidatorConfiguration configuration,
                               @Property(name = WARNINGS_ENABLED, defaultValue = StringUtils.TRUE) boolean warningsEnabled) {
        super(configuration);
        this.configuration = configuration;
        this.reflectionMessageInterpolator = configuration.getMessageInterpolator();
        this.reflectionClockProvider = configuration.getClockProvider();
        this.reflectionTraversableResolver = configuration.getTraversableResolver();
        this.reflectionValueExtractorRegistry = configuration.getValueExtractorRegistry();
        this.warningsEnabled = warningsEnabled;
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groups) {
        requireNonNull(ARGUMENT_OBJECT, object);
        BeanValidationContext context = BeanValidationContext.fromGroups(groups);
        return validateObject(object, context, () -> super.validate(object, groups));
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validate(T object, @Nullable BeanValidationContext validationContext) {
        requireNonNull(ARGUMENT_OBJECT, object);
        BeanValidationContext context = validationContext == null ? BeanValidationContext.DEFAULT : validationContext;
        return validateObject(object, context, () -> super.validate(object, context));
    }

    private <T> Set<ConstraintViolation<T>> validateObject(T object,
                                                           BeanValidationContext context,
                                                           Supplier<Set<ConstraintViolation<T>>> introspectionValidator) {
        Class<?> objectType = object.getClass();
        if (ReflectionGroupSequences.hasInheritedDefaultGroupSequence(objectType, context, configuration.getMetadataProviders())) {
            return validateReflectivelyWithInheritedDefaultGroupSequence(object, false);
        }
        BeanIntrospection<T> introspection = getBeanIntrospection(object);
        if (introspection == null) {
            return validateReflectively(object, context, false);
        }
        if (ReflectionGroupSequences.hasDefaultGroupSequence(objectType, context, configuration.getMetadataProviders())) {
            return validateReflectively(object, context, false);
        }
        return validateIntrospectedObject(object, objectType, context, introspectionValidator);
    }

    private <T> Set<ConstraintViolation<T>> validateIntrospectedObject(T object,
                                                                       Class<?> objectType,
                                                                       BeanValidationContext context,
                                                                       Supplier<Set<ConstraintViolation<T>>> introspectionValidator) {
        boolean reflectionCascadingAuthoritative = hasReflectionCascadedProperties(objectType);
        boolean reflectionPropertyAccessAuthoritative = hasReflectionRequiredPropertyAccess(objectType);
        Set<ConstraintViolation<T>> reflected = validateReflectively(object, context, !(reflectionCascadingAuthoritative || reflectionPropertyAccessAuthoritative));
        if (reflectionCascadingAuthoritative || reflectionPropertyAccessAuthoritative) {
            return reflected;
        }
        boolean reflectionAuthoritative = !reflected.isEmpty() || hasReflectionRequiredConstraints(objectType);
        if (hasReflectionConstraintValidators(objectType) && !hasReflectionContainerElements(objectType)) {
            return reflected;
        }
        try {
            return mergeViolations(introspectionValidator.get(), reflected);
        } catch (UnexpectedTypeException e) {
            if (reflectionAuthoritative) {
                return reflected;
            }
            throw e;
        }
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName, @Nullable BeanValidationContext context) {
        requireNonNull(ARGUMENT_OBJECT, object);
        requireNonEmpty("propertyName", propertyName);
        BeanIntrospection<T> introspection = getBeanIntrospection(object);
        if (introspection != null) {
            BeanValidationContext validationContext = context == null ? BeanValidationContext.DEFAULT : context;
            ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(object.getClass(), configuration.getMetadataProviders());
            if (metadata.properties.containsKey(propertyName)) {
                return validatePropertiesReflectively(object, propertyName, validationContext, metadata);
            }
            return super.validateProperty(object, propertyName, validationContext);
        }
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(object.getClass(), configuration.getMetadataProviders());
        List<ReflectionProperty> properties = metadata.properties.get(propertyName);
        if (properties == null) {
            throw new IllegalArgumentException("No property [" + propertyName + "] found on type: " + object.getClass());
        }
        return validatePropertiesReflectively(object, propertyName, context == null ? BeanValidationContext.DEFAULT : context, metadata);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateValue(Class<T> beanType, String propertyName, @Nullable Object value, @Nullable BeanValidationContext context) {
        requireNonNull("beanType", beanType);
        requireNonEmpty("propertyName", propertyName);
        BeanIntrospection<T> introspection = getBeanIntrospection(beanType);
        if (introspection != null) {
            BeanValidationContext validationContext = context == null ? BeanValidationContext.DEFAULT : context;
            ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(beanType, configuration.getMetadataProviders());
            if (metadata.properties.containsKey(propertyName)) {
                return validateValueReflectively(beanType, propertyName, value, validationContext, metadata);
            }
            return super.validateValue(beanType, propertyName, value, validationContext);
        }
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(beanType, configuration.getMetadataProviders());
        List<ReflectionProperty> properties = metadata.properties.get(propertyName);
        if (properties == null) {
            throw new IllegalArgumentException("No property [" + propertyName + "] found on type: " + beanType);
        }
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        BeanValidationContext validationContext = context == null ? BeanValidationContext.DEFAULT : context;
        for (ReflectionProperty property : properties) {
            validateConstraints(null, beanType, null, value, property.type, property.constraints, validationContext, violations, new ReflectionPath(property.name));
        }
        return Collections.unmodifiableSet(violations);
    }

    @Override
    public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
        requireNonNull("clazz", clazz);
        BeanDescriptor generated = super.getConstraintsForClass(clazz);
        ReflectionBeanMetadata reflected = ReflectionBeanMetadata.of(clazz, configuration.getMetadataProviders());
        if (getBeanIntrospection(clazz) != null) {
            return new ReflectionSupplementedBeanDescriptor(generated, reflected, clazz, configuration.getMetadataProviders());
        }
        if (hasProviderBeanDescriptor(clazz) && isBeanAnnotationMetadataIgnored(clazz) && !generated.isBeanConstrained()) {
            return generated;
        }
        return generated.isBeanConstrained()
            ? new ReflectionSupplementedBeanDescriptor(generated, reflected, clazz, configuration.getMetadataProviders())
            : reflected;
    }

    private boolean hasProviderBeanDescriptor(Class<?> beanType) {
        return configuration.getMetadataProviders()
            .stream()
            .anyMatch(provider -> provider.getConstraintsForClass(beanType).isPresent());
    }

    private boolean isBeanAnnotationMetadataIgnored(Class<?> beanType) {
        return configuration.getMetadataProviders()
            .stream()
            .anyMatch(provider -> provider.isBeanAnnotationMetadataIgnored(beanType));
    }

    private boolean isPropertyAnnotationMetadataIgnored(Class<?> beanType, String propertyName) {
        return configuration.getMetadataProviders()
            .stream()
            .anyMatch(provider -> provider.isPropertyAnnotationMetadataIgnored(beanType, propertyName));
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateParameters(T object, Method method, Object[] parameterValues, Class<?>... groups) {
        requireNonNull(ARGUMENT_OBJECT, object);
        requireNonNull("method", method);
        requireNonNull("parameterValues", parameterValues);
        requireNonNull(ARGUMENT_GROUPS, groups);
        BeanValidationContext context = BeanValidationContext.fromGroups(groups);
        Set<ConstraintViolation<T>> reflectedViolations = validateExecutableGroupPasses(
            object.getClass(),
            context,
            (groupContext, cascadedContext) -> validateParametersReflectively(object, method, parameterValues, groupContext, cascadedContext),
            violation -> violation.getLeafBean() == object
        );
        if (!reflectedViolations.isEmpty()) {
            return reflectedViolations;
        }
        if (hasReflectiveParameterValidation(method, object.getClass())) {
            return reflectedViolations;
        }
        Set<ConstraintViolation<T>> generatedViolations;
        UnexpectedTypeException generatedException = null;
        try {
            generatedViolations = filterGeneratedExecutableViolations(
                method,
                super.validateParameters(object, method, parameterValues, groups),
                ConstraintTarget.PARAMETERS
            );
        } catch (UnexpectedTypeException e) {
            generatedViolations = Collections.emptySet();
            generatedException = e;
        }
        if (generatedException != null) {
            throw generatedException;
        }
        return generatedViolations;
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateReturnValue(T object, Method method, @Nullable Object returnValue, Class<?>... groups) {
        requireNonNull(ARGUMENT_OBJECT, object);
        requireNonNull("method", method);
        requireNonNull(ARGUMENT_GROUPS, groups);
        BeanValidationContext context = BeanValidationContext.fromGroups(groups);
        Set<ConstraintViolation<T>> reflectedViolations = validateExecutableGroupPasses(
            object.getClass(),
            context,
            (groupContext, cascadedContext) -> validateReturnValueReflectively(object, method, returnValue, groupContext, cascadedContext),
            violation -> violation.getLeafBean() == object
        );
        if (!reflectedViolations.isEmpty()) {
            return reflectedViolations;
        }
        if (hasReflectiveReturnValueValidation(method)) {
            return reflectedViolations;
        }
        Set<ConstraintViolation<T>> generatedViolations = filterGeneratedExecutableViolations(
            method,
            super.validateReturnValue(object, method, returnValue, groups),
            ConstraintTarget.RETURN_VALUE
        );
        return generatedViolations;
    }

    private static boolean hasReflectiveParameterValidation(Method method, Class<?> beanType) {
        if (!crossParameterConstraintsFor(method, beanType).isEmpty()) {
            return true;
        }
        for (ReflectionExecutableParameter parameter : parametersFor(method)) {
            if (!parameter.constraints.isEmpty()
                || parameter.cascaded
                || !parameter.groupConversions.isEmpty()
                || !parameter.containerElements.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasReflectiveReturnValueValidation(Method method) {
        ReflectionExecutableReturnValue returnValue = returnValueFor(method);
        return !returnValue.constraints.isEmpty()
            || returnValue.cascaded
            || !returnValue.groupConversions.isEmpty()
            || !returnValue.containerElements.isEmpty();
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(Constructor<? extends T> constructor, Object[] parameterValues, Class<?>... groups) {
        requireNonNull("constructor", constructor);
        requireNonNull("parameterValues", parameterValues);
        requireNonNull(ARGUMENT_GROUPS, groups);
        Set<ConstraintViolation<T>> generatedViolations = Collections.emptySet();
        BeanIntrospection<? extends T> introspection = getBeanIntrospection(constructor.getDeclaringClass());
        if (introspection != null && introspection.getConstructorArguments().length == constructor.getParameterCount()) {
            generatedViolations = super.validateConstructorParameters(constructor, parameterValues, groups);
        }
        BeanValidationContext context = BeanValidationContext.fromGroups(groups);
        Set<ConstraintViolation<T>> reflectedViolations = validateExecutableGroupPasses(
            constructor.getDeclaringClass(),
            context,
            (groupContext, cascadedContext) -> validateConstructorParametersReflectively(constructor, parameterValues, groupContext, cascadedContext),
            violation -> violation.getLeafBean() == null
        );
        return reflectedViolations.isEmpty() ? generatedViolations : reflectedViolations;
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorReturnValue(Constructor<? extends T> constructor,
                                                                          T createdObject,
                                                                          Class<?>... groups) {
        requireNonNull("constructor", constructor);
        requireNonNull("createdObject", createdObject);
        requireNonNull(ARGUMENT_GROUPS, groups);
        BeanValidationContext context = BeanValidationContext.fromGroups(groups);
        return validateExecutableGroupPasses(
            constructor.getDeclaringClass(),
            context,
            (groupContext, cascadedContext) -> validateConstructorReturnValueReflectively(constructor, createdObject, groupContext, cascadedContext),
            violation -> violation.getLeafBean() == createdObject
        );
    }

    private <T> Set<ConstraintViolation<T>> validateExecutableGroupPasses(Class<?> beanType,
                                                                          BeanValidationContext context,
                                                                          BiFunction<BeanValidationContext, BeanValidationContext, Set<ConstraintViolation<T>>> validator,
                                                                          Predicate<ConstraintViolation<T>> sequenceBlockingViolation) {
        boolean defaultGroupRedefined = ReflectionGroupSequences.hasDefaultGroupSequence(beanType, context, configuration.getMetadataProviders());
        Set<ConstraintViolation<T>> collectedViolations = defaultGroupRedefined ? new LinkedHashSet<>() : Collections.emptySet();
        for (List<Class<?>> groupPass : ReflectionGroupSequences.validationGroupPasses(beanType, context, configuration.getMetadataProviders())) {
            BeanValidationContext groupContext = BeanValidationContext.fromGroups(groupPass.toArray(Class<?>[]::new));
            BeanValidationContext cascadedContext = defaultGroupRedefined ? BeanValidationContext.DEFAULT : groupContext;
            Set<ConstraintViolation<T>> violations = validator.apply(groupContext, cascadedContext);
            if (!violations.isEmpty()) {
                if (!defaultGroupRedefined) {
                    return violations;
                }
                collectedViolations = new LinkedHashSet<>(mergeViolations(collectedViolations, violations));
                if (violations.stream().anyMatch(sequenceBlockingViolation)) {
                    return Collections.unmodifiableSet(collectedViolations);
                }
            }
        }
        return defaultGroupRedefined ? Collections.unmodifiableSet(collectedViolations) : Collections.emptySet();
    }

    private <T> Set<ConstraintViolation<T>> validateReflectively(T object,
                                                                 BeanValidationContext context,
                                                                 boolean supplementIntrospection) {
        ReflectionGroupConversions.validateBean(object.getClass());
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(object.getClass(), configuration.getMetadataProviders());
        warnOnce(object.getClass().getName(), "class", supplementIntrospection
            ? "supplementing Micronaut bean introspection with reflection metadata"
            : "validating without Micronaut bean introspection");
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        boolean defaultGroupRedefined = ReflectionGroupSequences.hasDefaultGroupSequence(object.getClass(), context, configuration.getMetadataProviders());
        Set<String> defaultGroupCascadedProperties = defaultGroupRedefined ? new LinkedHashSet<>() : null;
        for (List<Class<?>> groupPass : ReflectionGroupSequences.validationGroupPasses(object.getClass(), context, configuration.getMetadataProviders())) {
            int violationCount = violations.size();
            long blockingViolationCount = countLeafBeanViolations(violations, object);
            BeanValidationContext groupContext = BeanValidationContext.fromGroups(groupPass.toArray(Class<?>[]::new));
            BeanValidationContext cascadedContext = defaultGroupRedefined ? BeanValidationContext.DEFAULT : groupContext;
            Set<String> cascadedProperties = defaultGroupCascadedProperties == null ? new LinkedHashSet<>() : defaultGroupCascadedProperties;
            validateReflectionGroupPass(object, metadata, groupContext, cascadedContext, cascadedProperties, violations, supplementIntrospection);
            if (violations.size() > violationCount
                && (!defaultGroupRedefined || countLeafBeanViolations(violations, object) > blockingViolationCount)) {
                break;
            }
        }
        return Collections.unmodifiableSet(violations);
    }

    private <T> Set<ConstraintViolation<T>> validateReflectivelyWithInheritedDefaultGroupSequence(T object,
                                                                                                  boolean supplementIntrospection) {
        ReflectionGroupConversions.validateBean(object.getClass());
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(object.getClass(), configuration.getMetadataProviders());
        warnOnce(object.getClass().getName(), "class", supplementIntrospection
            ? "supplementing Micronaut bean introspection with reflection metadata"
            : "validating without Micronaut bean introspection");
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        Set<String> cascadedProperties = new LinkedHashSet<>();
        validateReflectionGroupPass(object, metadata, BeanValidationContext.fromGroups(object.getClass()), BeanValidationContext.DEFAULT, cascadedProperties, violations, supplementIntrospection);
        for (List<Class<?>> groupPass : ReflectionGroupSequences.inheritedDefaultGroupSequencePasses(object.getClass(), configuration.getMetadataProviders())) {
            int violationCount = violations.size();
            long blockingViolationCount = countLeafBeanViolations(violations, object);
            BeanValidationContext groupContext = BeanValidationContext.fromGroups(groupPass.toArray(Class<?>[]::new));
            validateReflectionGroupPass(object, metadata, groupContext, BeanValidationContext.DEFAULT, cascadedProperties, violations, supplementIntrospection);
            if (violations.size() > violationCount && countLeafBeanViolations(violations, object) > blockingViolationCount) {
                break;
            }
        }
        return Collections.unmodifiableSet(violations);
    }

    private static long countLeafBeanViolations(Set<? extends ConstraintViolation<?>> violations, Object leafBean) {
        return violations.stream()
            .filter(violation -> isSequenceBlockingViolation(violation, leafBean))
            .count();
    }

    private static boolean isSequenceBlockingViolation(ConstraintViolation<?> violation, Object leafBean) {
        if (violation.getLeafBean() != leafBean) {
            return false;
        }
        Iterator<Path.Node> nodes = violation.getPropertyPath().iterator();
        if (!nodes.hasNext()) {
            return true;
        }
        nodes.next();
        return !nodes.hasNext();
    }

    private <T> void validateReflectionGroupPass(T object,
                                                 ReflectionBeanMetadata metadata,
                                                 BeanValidationContext groupContext,
                                                 BeanValidationContext cascadedContext,
                                                 Set<String> cascadedProperties,
                                                 Set<ConstraintViolation<T>> violations,
                                                 boolean supplementIntrospection) {
        Map<ConstraintKey, Integer> generatedTypeConstraints = supplementIntrospection
            ? generatedTypeConstraints(object.getClass())
            : Map.of();
        boolean ignoreBeanAnnotations = isBeanAnnotationMetadataIgnored(object.getClass());
        List<ReflectionConstraintDescriptor<?>> typeConstraints = new ArrayList<>(
            ignoreBeanAnnotations ? List.of() : supplementalConstraints(metadata.constraints, generatedTypeConstraints, object.getClass())
        );
        typeConstraints.addAll(providerTypeConstraints(object.getClass()));
        validateConstraints(
            object,
            object.getClass(),
            object,
            object,
            object.getClass(),
            typeConstraints,
            groupContext,
            violations,
            new ReflectionPath(null)
        );
        for (Map.Entry<String, List<ReflectionProperty>> entry : metadata.properties.entrySet()) {
            boolean ignorePropertyAnnotations = isPropertyAnnotationMetadataIgnored(object.getClass(), entry.getKey());
            ProviderPropertyMetadata providerMetadata = providerPropertyMetadata(object.getClass(), entry.getKey());
            if (ignorePropertyAnnotations && providerMetadata.isEmpty()) {
                continue;
            }
            List<ReflectionProperty> properties = entry.getValue();
            boolean suppressGeneratedPropertyConstraints = supplementIntrospection && properties.size() == 1;
            for (ReflectionProperty property : properties) {
                validateProperty(
                    object,
                    object,
                    object.getClass(),
                    effectiveProperty(property, ignorePropertyAnnotations, providerMetadata),
                    groupContext,
                    cascadedContext,
                    violations,
                    suppressGeneratedPropertyConstraints,
                    true,
                    true,
                    null,
                    cascadedProperties
                );
            }
        }
        validateProviderOnlyProperties(object, metadata, groupContext, cascadedContext, cascadedProperties, violations);
    }

    private Map<ConstraintKey, Integer> generatedTypeConstraints(Class<?> beanType) {
        BeanDescriptor descriptor = super.getConstraintsForClass(beanType);
        Map<ConstraintKey, Integer> counts = new LinkedHashMap<>();
        for (ConstraintDescriptor<?> constraintDescriptor : descriptor.getConstraintDescriptors()) {
            counts.merge(ConstraintKey.of(constraintDescriptor), 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }

    private Map<ConstraintKey, Integer> generatedPropertyConstraints(Class<?> beanType, String propertyName) {
        BeanIntrospection<?> introspection = getBeanIntrospection(beanType);
        if (introspection == null) {
            return Map.of();
        }
        BeanProperty<?, ?> beanProperty = introspection.getProperty(propertyName).orElse(null);
        if (beanProperty == null) {
            return Map.of();
        }
        return constraintCounts(beanProperty.getAnnotationMetadata());
    }

    private Map<ConstraintKey, Integer> generatedPropertyConstraints(Class<?> beanType, ReflectionProperty property) {
        if (property.declaringClass() != beanType) {
            return Map.of();
        }
        return generatedPropertyConstraints(beanType, property.name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<ConstraintKey, Integer> constraintCounts(AnnotationMetadata annotationMetadata) {
        Map<ConstraintKey, Integer> counts = new LinkedHashMap<>();
        Set<String> declaredAnnotationNames = annotationMetadata.getDeclaredAnnotationNames();
        List<Class<? extends Annotation>> constraintTypes = annotationMetadata.getAnnotationTypesByStereotype(Constraint.class);
        boolean hasDeclaredConstraint = constraintTypes.stream().anyMatch(type -> isDeclaredConstraint(declaredAnnotationNames, type));
        for (Class<? extends Annotation> type : constraintTypes) {
            if (hasDeclaredConstraint && !isDeclaredConstraint(declaredAnnotationNames, type)) {
                continue;
            }
            for (AnnotationValue<? extends Annotation> annotationValue : annotationMetadata.getAnnotationValuesByType(type)) {
                counts.merge(ConstraintKey.of((Class) type, annotationValue), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static boolean isDeclaredConstraint(Set<String> declaredAnnotationNames,
                                                Class<? extends Annotation> constraintType) {
        String constraintName = constraintType.getName();
        return declaredAnnotationNames.contains(constraintName)
            || declaredAnnotationNames.contains(constraintName + "$List");
    }

    private static List<ReflectionConstraintDescriptor<?>> supplementalConstraints(
        List<ReflectionConstraintDescriptor<?>> constraints,
        Map<ConstraintKey, Integer> generatedConstraints,
        Class<?> valueType) {
        if (generatedConstraints.isEmpty()) {
            return constraints;
        }
        Map<ConstraintKey, Integer> reflectedConstraints = new LinkedHashMap<>();
        for (ReflectionConstraintDescriptor<?> constraint : constraints) {
            reflectedConstraints.merge(ConstraintKey.of(constraint), 1, Integer::sum);
        }
        Map<ConstraintKey, Integer> remainingGeneratedConstraints = new LinkedHashMap<>(generatedConstraints);
        List<ReflectionConstraintDescriptor<?>> supplemental = new ArrayList<>(constraints.size());
        for (ReflectionConstraintDescriptor<?> constraint : constraints) {
            ConstraintKey key = ConstraintKey.of(constraint);
            int remaining = remainingGeneratedConstraints.getOrDefault(key, 0);
            if (remaining > 0
                && reflectedConstraints.getOrDefault(key, 0) <= generatedConstraints.getOrDefault(key, 0)
                && !requiresReflectionValidation(constraint, valueType)) {
                remainingGeneratedConstraints.put(key, remaining - 1);
            } else {
                supplemental.add(constraint);
            }
        }
        return supplemental;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean hasAmbiguousValidatorResolution(ReflectionConstraintDescriptor<?> constraint, Class<?> valueType) {
        try {
            ReflectionConstraintValidatorResolution.resolve(
                constraint.getType(),
                (List) constraint.getConstraintValidatorClasses(),
                valueType,
                ConstraintTarget.IMPLICIT
            );
            return false;
        } catch (UnexpectedTypeException e) {
            return true;
        }
    }

    private static List<ReflectionConstraintDescriptor<?>> supplementalPropertyConstraints(
        List<ReflectionConstraintDescriptor<?>> constraints,
        Map<ConstraintKey, Integer> generatedConstraints,
        Class<?> valueType,
        BeanValidationContext context) {
        if (generatedConstraints.isEmpty()) {
            return constraints;
        }
        Map<ConstraintKey, Integer> reflectedConstraints = new LinkedHashMap<>();
        for (ReflectionConstraintDescriptor<?> constraint : constraints) {
            reflectedConstraints.merge(ConstraintKey.of(constraint), 1, Integer::sum);
        }
        Map<ConstraintKey, Integer> remainingGeneratedConstraints = new LinkedHashMap<>(generatedConstraints);
        List<ReflectionConstraintDescriptor<?>> supplemental = new ArrayList<>(constraints.size());
        for (ReflectionConstraintDescriptor<?> constraint : constraints) {
            ConstraintKey key = ConstraintKey.of(constraint);
            int remaining = remainingGeneratedConstraints.getOrDefault(key, 0);
            if (remaining > 0
                && reflectedConstraints.getOrDefault(key, 0) <= generatedConstraints.getOrDefault(key, 0)
                && !constraint.matchesImplicitGroup(context)
                && !requiresReflectionValidation(constraint, valueType)) {
                remainingGeneratedConstraints.put(key, remaining - 1);
            } else {
                supplemental.add(constraint);
            }
        }
        return supplemental;
    }

    private static boolean requiresReflectionValidation(ReflectionConstraintDescriptor<?> constraint, Class<?> valueType) {
        return hasAmbiguousValidatorResolution(constraint, valueType)
            || supportsMinMaxReflection(constraint, valueType)
            || unsupportedBooleanConstraintType(constraint, valueType)
            || !constraint.getConstraintValidatorClasses().isEmpty()
            || constraint.getValueUnwrapping() == ValidateUnwrappedValue.UNWRAP
            || constraint.hasValidationAppliesTo()
            || !constraint.composingConstraints.isEmpty();
    }

    private static boolean unsupportedBooleanConstraintType(ReflectionConstraintDescriptor<?> constraint, Class<?> valueType) {
        Class<?> constraintType = constraint.getType();
        return (constraintType == AssertTrue.class || constraintType == AssertFalse.class)
            && ReflectionUtils.getWrapperType(valueType) != Boolean.class;
    }

    private boolean hasReflectionConstraintValidators(Class<?> beanType) {
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(beanType, configuration.getMetadataProviders());
        if (metadata.constraints.stream().anyMatch(constraint -> !constraint.getConstraintValidatorClasses().isEmpty())) {
            return true;
        }
        return metadata.properties.values()
            .stream()
            .flatMap(List::stream)
            .flatMap(property -> property.constraints.stream())
            .anyMatch(constraint -> !constraint.getConstraintValidatorClasses().isEmpty());
    }

    private boolean hasReflectionContainerElements(Class<?> beanType) {
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(beanType, configuration.getMetadataProviders());
        return metadata.properties.values()
            .stream()
            .flatMap(List::stream)
            .anyMatch(property -> !property.containerElements.isEmpty());
    }

    private boolean hasReflectionRequiredConstraints(Class<?> beanType) {
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(beanType, configuration.getMetadataProviders());
        for (ReflectionConstraintDescriptor<?> constraint : metadata.constraints) {
            if (requiresReflectionValidation(constraint, beanType)) {
                return true;
            }
        }
        for (List<ReflectionProperty> properties : metadata.properties.values()) {
            for (ReflectionProperty property : properties) {
                for (ReflectionConstraintDescriptor<?> constraint : property.constraints) {
                    if (requiresReflectionValidation(constraint, property.type)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasReflectionCascadedProperties(Class<?> beanType) {
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(beanType);
        for (List<ReflectionProperty> properties : metadata.properties.values()) {
            for (ReflectionProperty property : properties) {
                if (property.isCascaded() || property.containerElements.stream().anyMatch(ReflectionContainerElement::cascaded)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasReflectionRequiredPropertyAccess(Class<?> beanType) {
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(beanType);
        for (List<ReflectionProperty> properties : metadata.properties.values()) {
            boolean constrainedField = false;
            boolean constrainedGetter = false;
            for (ReflectionProperty property : properties) {
                if (property.constraints.isEmpty()) {
                    continue;
                }
                if (property.source instanceof Field) {
                    constrainedField = true;
                } else {
                    constrainedGetter = true;
                }
                if (constrainedField && constrainedGetter) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean supportsMinMaxReflection(ReflectionConstraintDescriptor<?> constraint, Class<?> valueType) {
        Class<?> constraintType = constraint.getType();
        return (CharSequence.class.isAssignableFrom(valueType) || Number.class.isAssignableFrom(valueType))
            && (constraintType == Min.class || constraintType == Max.class);
    }

    private <T> Set<ConstraintViolation<T>> validatePropertyReflectively(T object,
                                                                         String propertyName,
                                                                         BeanValidationContext context) {
        return validatePropertiesReflectively(object, propertyName, context, ReflectionBeanMetadata.of(object.getClass(), configuration.getMetadataProviders()));
    }

    private <T> Set<ConstraintViolation<T>> validatePropertiesReflectively(T object,
                                                                          String propertyName,
                                                                          BeanValidationContext context,
                                                                          ReflectionBeanMetadata metadata) {
        List<ReflectionProperty> properties = metadata.properties.get(propertyName);
        if (properties == null) {
            return Collections.emptySet();
        }
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        for (List<Class<?>> groupPass : ReflectionGroupSequences.validationGroupPasses(object.getClass(), context, configuration.getMetadataProviders())) {
            int violationCount = violations.size();
            BeanValidationContext groupContext = BeanValidationContext.fromGroups(groupPass.toArray(Class<?>[]::new));
            boolean ignorePropertyAnnotations = isPropertyAnnotationMetadataIgnored(object.getClass(), propertyName);
            ProviderPropertyMetadata providerMetadata = providerPropertyMetadata(object.getClass(), propertyName);
            Set<String> cascadedProperties = new LinkedHashSet<>();
            for (ReflectionProperty property : properties) {
                validateProperty(
                    object,
                    object,
                    object.getClass(),
                    effectiveProperty(property, ignorePropertyAnnotations, providerMetadata),
                    groupContext,
                    groupContext,
                    violations,
                    false,
                    true,
                    false,
                    null,
                    cascadedProperties
                );
            }
            if (violations.size() > violationCount) {
                break;
            }
        }
        return Collections.unmodifiableSet(violations);
    }

    private <T> Set<ConstraintViolation<T>> validateValueReflectively(Class<T> beanType,
                                                                      String propertyName,
                                                                      @Nullable Object value,
                                                                      BeanValidationContext context,
                                                                      ReflectionBeanMetadata metadata) {
        List<ReflectionProperty> properties = metadata.properties.get(propertyName);
        if (properties == null) {
            return Collections.emptySet();
        }
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        for (List<Class<?>> groupPass : ReflectionGroupSequences.validationGroupPasses(beanType, context, configuration.getMetadataProviders())) {
            int violationCount = violations.size();
            BeanValidationContext groupContext = BeanValidationContext.fromGroups(groupPass.toArray(Class<?>[]::new));
            for (ReflectionProperty property : properties) {
                if (!isReachable(null, property, beanType, null)) {
                    continue;
                }
                boolean ignorePropertyAnnotations = isPropertyAnnotationMetadataIgnored(beanType, propertyName);
                ReflectionProperty effectiveProperty = effectiveProperty(property, ignorePropertyAnnotations, providerPropertyMetadata(beanType, propertyName));
                validateConstraints(null, beanType, null, value, effectiveProperty.type, effectiveProperty.constraints, groupContext, violations, new ReflectionPath(effectiveProperty.name));
            }
            if (violations.size() > violationCount) {
                break;
            }
        }
        return Collections.unmodifiableSet(violations);
    }

    private ReflectionProperty effectiveProperty(ReflectionProperty property,
                                                 boolean ignorePropertyAnnotations,
                                                 ProviderPropertyMetadata providerMetadata) {
        if (!ignorePropertyAnnotations && providerMetadata.isEmpty()) {
            return property;
        }
        List<ReflectionConstraintDescriptor<?>> constraints = new ArrayList<>(ignorePropertyAnnotations ? List.of() : property.constraints);
        constraints.addAll(providerMetadata.constraints);
        Set<ReflectionGroupConversionDescriptor> groupConversions = new LinkedHashSet<>(ignorePropertyAnnotations ? Set.of() : property.groupConversions);
        groupConversions.addAll(providerMetadata.groupConversions);
        List<ReflectionContainerElement> containerElements = new ArrayList<>(ignorePropertyAnnotations ? List.of() : property.containerElements);
        addAllContainerElements(containerElements, providerMetadata.containerElements);
        return new ReflectionProperty(
            property.name,
            property.type,
            property.source,
            List.copyOf(constraints),
            Set.copyOf(groupConversions),
            List.copyOf(containerElements),
            providerMetadata.cascaded
        );
    }

    private <T> void validateProviderOnlyProperties(T object,
                                                    ReflectionBeanMetadata metadata,
                                                    BeanValidationContext groupContext,
                                                    BeanValidationContext cascadedContext,
                                                    Set<String> cascadedProperties,
                                                    Set<ConstraintViolation<T>> violations) {
        for (ReflectionProperty property : providerOnlyProperties(object.getClass(), metadata.properties.keySet())) {
            validateProperty(object, object, object.getClass(), property, groupContext, cascadedContext, violations, false, true, true, null, cascadedProperties);
        }
    }

    private List<ReflectionProperty> providerOnlyProperties(Class<?> beanType, Set<String> reflectedPropertyNames) {
        List<ReflectionProperty> properties = new ArrayList<>();
        for (ValidationMetadataProvider provider : configuration.getMetadataProviders()) {
            if (provider instanceof ReflectionValidationMetadataProvider) {
                continue;
            }
            BeanDescriptor beanDescriptor = provider.getConstraintsForClass(beanType).orElse(null);
            if (beanDescriptor == null) {
                continue;
            }
            for (PropertyDescriptor propertyDescriptor : beanDescriptor.getConstrainedProperties()) {
                if (reflectedPropertyNames.contains(propertyDescriptor.getPropertyName())) {
                    continue;
                }
                AccessibleObject source = findPropertySource(beanType, propertyDescriptor.getPropertyName());
                if (source == null) {
                    continue;
                }
                properties.add(new ReflectionProperty(
                    propertyDescriptor.getPropertyName(),
                    propertyDescriptor.getElementClass(),
                    source,
                    constraints(propertyDescriptor.getConstraintDescriptors(), configuration.getMetadataProviders()),
                    groupConversions(propertyDescriptor.getGroupConversions()),
                    containerElements(propertyDescriptor.getConstrainedContainerElementTypes(), configuration.getMetadataProviders()),
                    propertyDescriptor.isCascaded()
                ));
            }
        }
        return List.copyOf(properties);
    }

    private static @Nullable AccessibleObject findPropertySource(Class<?> beanType, String propertyName) {
        Class<?> current = beanType;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(propertyName);
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    return field;
                }
            } catch (NoSuchFieldException e) {
                // Continue with getter lookup.
            }
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() == 0
                    && !java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    && propertyName.equals(ReflectionBeanMetadata.propertyName(method))) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private List<ReflectionConstraintDescriptor<?>> providerTypeConstraints(Class<?> beanType) {
        List<ReflectionConstraintDescriptor<?>> constraints = new ArrayList<>();
        for (ValidationMetadataProvider provider : configuration.getMetadataProviders()) {
            if (provider instanceof ReflectionValidationMetadataProvider) {
                continue;
            }
            BeanDescriptor beanDescriptor = provider.getConstraintsForClass(beanType).orElse(null);
            if (beanDescriptor != null) {
                constraints.addAll(constraints(beanDescriptor.getConstraintDescriptors(), configuration.getMetadataProviders()));
            }
        }
        return List.copyOf(constraints);
    }

    private ProviderPropertyMetadata providerPropertyMetadata(Class<?> beanType, String propertyName) {
        List<ReflectionConstraintDescriptor<?>> constraints = new ArrayList<>();
        Set<ReflectionGroupConversionDescriptor> groupConversions = new LinkedHashSet<>();
        List<ReflectionContainerElement> containerElements = new ArrayList<>();
        boolean cascaded = false;
        for (ValidationMetadataProvider provider : configuration.getMetadataProviders()) {
            if (provider instanceof ReflectionValidationMetadataProvider) {
                continue;
            }
            BeanDescriptor beanDescriptor = provider.getConstraintsForClass(beanType).orElse(null);
            if (beanDescriptor == null) {
                continue;
            }
            PropertyDescriptor propertyDescriptor = beanDescriptor.getConstraintsForProperty(propertyName);
            if (propertyDescriptor != null) {
                constraints.addAll(constraints(propertyDescriptor.getConstraintDescriptors(), configuration.getMetadataProviders()));
                groupConversions.addAll(groupConversions(propertyDescriptor.getGroupConversions()));
                containerElements.addAll(containerElements(propertyDescriptor.getConstrainedContainerElementTypes(), configuration.getMetadataProviders()));
                cascaded = cascaded || propertyDescriptor.isCascaded();
            }
        }
        return new ProviderPropertyMetadata(
            List.copyOf(constraints),
            Set.copyOf(groupConversions),
            List.copyOf(containerElements),
            cascaded
        );
    }

    private record ProviderPropertyMetadata(
        List<ReflectionConstraintDescriptor<?>> constraints,
        Set<ReflectionGroupConversionDescriptor> groupConversions,
        List<ReflectionContainerElement> containerElements,
        boolean cascaded
    ) {
        boolean isEmpty() {
            return constraints.isEmpty() && groupConversions.isEmpty() && containerElements.isEmpty() && !cascaded;
        }
    }

    private static List<ReflectionContainerElement> containerElements(Set<ContainerElementTypeDescriptor> descriptors) {
        return containerElements(descriptors, List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<ReflectionContainerElement> containerElements(Set<ContainerElementTypeDescriptor> descriptors,
                                                                      List<ValidationMetadataProvider> metadataProviders) {
        if (descriptors.isEmpty()) {
            return List.of();
        }
        List<ReflectionContainerElement> containerElements = new ArrayList<>(descriptors.size());
        for (ContainerElementTypeDescriptor descriptor : descriptors) {
            List<ReflectionConstraintDescriptor<?>> constraints = constraints(descriptor.getConstraintDescriptors(), metadataProviders);
            Set<ReflectionGroupConversionDescriptor> groupConversions = descriptor.getGroupConversions()
                .stream()
                .map(groupConversion -> new ReflectionGroupConversionDescriptor(groupConversion.getFrom(), groupConversion.getTo()))
                .collect(Collectors.toUnmodifiableSet());
            containerElements.add(new ReflectionContainerElement(
                descriptor.getContainerClass(),
                descriptor.getTypeArgumentIndex(),
                descriptor.getElementClass(),
                constraints,
                descriptor.isCascaded(),
                groupConversions,
                containerElements(descriptor.getConstrainedContainerElementTypes(), metadataProviders),
                true
            ));
        }
        return List.copyOf(containerElements);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<ReflectionConstraintDescriptor<?>> constraints(Set<ConstraintDescriptor<?>> descriptors) {
        return constraints(descriptors, List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<ReflectionConstraintDescriptor<?>> constraints(Set<ConstraintDescriptor<?>> descriptors,
                                                                       List<ValidationMetadataProvider> metadataProviders) {
        if (descriptors.isEmpty()) {
            return List.of();
        }
        List<ReflectionConstraintDescriptor<?>> constraints = new ArrayList<>(descriptors.size());
        for (ConstraintDescriptor<?> descriptor : descriptors) {
            constraints.add(new ReflectionConstraintDescriptor(descriptor.getAnnotation(), null, metadataProviders));
        }
        return List.copyOf(constraints);
    }

    private static Set<ReflectionGroupConversionDescriptor> groupConversions(Set<GroupConversionDescriptor> descriptors) {
        if (descriptors.isEmpty()) {
            return Set.of();
        }
        return descriptors.stream()
            .map(groupConversion -> new ReflectionGroupConversionDescriptor(groupConversion.getFrom(), groupConversion.getTo()))
            .collect(Collectors.toUnmodifiableSet());
    }

    private static <T> Set<ConstraintViolation<T>> mergeViolations(Set<ConstraintViolation<T>> existing,
                                                                   Set<ConstraintViolation<T>> reflected) {
        if (existing.isEmpty()) {
            return reflected;
        }
        if (reflected.isEmpty()) {
            return existing;
        }
        Map<ReflectionViolationKey, Integer> existingCounts = new LinkedHashMap<>();
        Map<ReflectionViolationKey, List<ConstraintViolation<T>>> existingViolations = new LinkedHashMap<>();
        for (ConstraintViolation<T> violation : existing) {
            ReflectionViolationKey key = ReflectionViolationKey.of(violation);
            existingCounts.merge(key, 1, Integer::sum);
            existingViolations.computeIfAbsent(key, ignored -> new ArrayList<>()).add(violation);
        }
        Set<ConstraintViolation<T>> merged = new LinkedHashSet<>(existing);
        for (ConstraintViolation<T> violation : reflected) {
            ReflectionViolationKey key = ReflectionViolationKey.of(violation);
            Integer remaining = existingCounts.get(key);
            if (remaining == null || remaining == 0) {
                merged.add(violation);
            } else {
                existingCounts.put(key, remaining - 1);
                ConstraintViolation<T> existingViolation = existingViolations.getOrDefault(key, List.of()).remove(0);
                merged.remove(existingViolation);
                merged.add(violation);
            }
        }
        return Collections.unmodifiableSet(merged);
    }

    private static <T> Set<ConstraintViolation<T>> filterGeneratedExecutableViolations(Method method,
                                                                                       Set<ConstraintViolation<T>> violations,
                                                                                       ConstraintTarget target) {
        if (violations.isEmpty()) {
            return violations;
        }
        Set<ConstraintViolation<T>> filtered = new LinkedHashSet<>(violations.size());
        boolean changed = false;
        for (ConstraintViolation<T> violation : violations) {
            if (isWrongExecutableTarget(method, violation.getConstraintDescriptor(), target)) {
                changed = true;
            } else {
                filtered.add(violation);
            }
        }
        return changed ? Collections.unmodifiableSet(filtered) : violations;
    }

    private static boolean isWrongExecutableTarget(Method method,
                                                   ConstraintDescriptor<?> descriptor,
                                                   ConstraintTarget target) {
        boolean foundMethodConstraint = false;
        for (Method hierarchyMethod : ReflectionMethodDeclarations.hierarchy(method)) {
            for (ReflectionConstraintDescriptor<?> methodConstraint : constraintsFor(hierarchyMethod)) {
                if (methodConstraint.getType() != descriptor.getAnnotation().annotationType()) {
                    continue;
                }
                foundMethodConstraint = true;
                if (isTargetedConstraint(methodConstraint, target)) {
                    return false;
                }
            }
        }
        return foundMethodConstraint;
    }

    @SuppressWarnings("unchecked")
    private static <T> Set<ConstraintViolation<T>> withExecutableParameters(Set<ConstraintViolation<T>> violations,
                                                                            Object[] executableParameters) {
        if (violations.isEmpty()) {
            return violations;
        }
        Set<ConstraintViolation<T>> mapped = new LinkedHashSet<>(violations.size());
        for (ConstraintViolation<T> violation : violations) {
            mapped.add(violation instanceof ReflectionConstraintViolation<?> reflectionViolation
                ? ((ReflectionConstraintViolation<T>) reflectionViolation).withExecutableParameters(executableParameters)
                : violation);
        }
        return Collections.unmodifiableSet(mapped);
    }

    @SuppressWarnings("unchecked")
    private static <T> Set<ConstraintViolation<T>> withExecutableReturnValue(Set<ConstraintViolation<T>> violations,
                                                                             @Nullable Object executableReturnValue) {
        if (violations.isEmpty()) {
            return violations;
        }
        Set<ConstraintViolation<T>> mapped = new LinkedHashSet<>(violations.size());
        for (ConstraintViolation<T> violation : violations) {
            mapped.add(violation instanceof ReflectionConstraintViolation<?> reflectionViolation
                ? ((ReflectionConstraintViolation<T>) reflectionViolation).withExecutableReturnValue(executableReturnValue)
                : violation);
        }
        return Collections.unmodifiableSet(mapped);
    }

    private static UnexpectedTypeException unexpectedType(ReflectionConstraintDescriptor<?> constraint,
                                                          Class<?> valueType) {
        return new UnexpectedTypeException("Cannot find a constraint validator for constraint: " + constraint.getType().getName() + " and type: " + valueType);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> Set<ConstraintViolation<T>> validateReturnValueReflectively(T object,
                                                                            Method method,
                                                                            @Nullable Object returnValue,
                                                                            BeanValidationContext context,
                                                                            BeanValidationContext cascadedContext) {
        warnOnce(method.getDeclaringClass().getName(), method.getName(), "validating executable return value without Micronaut executable metadata");
        ReflectionMethodDeclarations.validateReturnValueDeclarations(method);
        ReflectionGroupConversions.validateMethodReturnValueDeclarations(method);
        List<Method> methodHierarchy = ReflectionMethodDeclarations.hierarchy(method);
        boolean ignoreReturnValueAnnotations = isMethodReturnValueAnnotationMetadataIgnored(method);
        List<ReflectionConstraintDescriptor<?>> constraints = ignoreReturnValueAnnotations
            ? List.of()
            : methodHierarchy.stream()
                .flatMap(hierarchyMethod -> constraintsFor(hierarchyMethod).stream())
                .toList();
        List<ReflectionContainerElement> containerElements = ignoreReturnValueAnnotations
            ? new ArrayList<>()
            : new ArrayList<>(containerElementsFor(method.getAnnotatedReturnType()));
        addAllContainerElements(containerElements, providerReturnValueContainerElements(method));
        boolean cascaded = !ignoreReturnValueAnnotations && ReflectionMethodDeclarations.hasDirectCascadedReturnValueInHierarchy(method)
            || providerReturnValueCascaded(method);
        Set<ReflectionGroupConversionDescriptor> groupConversions = new LinkedHashSet<>();
        if (!ignoreReturnValueAnnotations) {
            for (Method hierarchyMethod : methodHierarchy) {
                groupConversions.addAll(groupConversionsFor(hierarchyMethod, hierarchyMethod.getAnnotatedReturnType()));
            }
        }
        groupConversions.addAll(providerReturnValueGroupConversions(method));
        if (constraints.isEmpty() && containerElements.isEmpty() && !cascaded) {
            return Collections.emptySet();
        }
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        ViolationTarget<T> target = new ViolationTarget<>(object, object.getClass(), object);
        for (ReflectionConstraintDescriptor constraint : constraints) {
            validateReturnValueConstraint(object, method, returnValue, context, violations, target, constraint);
        }
        validateExecutableContainerElements(
            object,
            object.getClass(),
            object,
            returnValue,
            method.getReturnType(),
            containerElements,
            context,
            violations,
            containerContext -> new ReflectionReturnValueContainerElementPath(method, containerContext)
        );
        if (cascaded) {
            validateCascadedValueOrContainer(
                object,
                object.getClass(),
                returnValue,
                returnValue,
                method.getReturnType(),
                convertGroups(cascadedContext, groupConversions),
                violations,
                new ReflectionReturnValueExecutablePath(method)
            );
        }
        return withExecutableReturnValue(violations, returnValue);
    }

    private <T> void validateReturnValueConstraint(T object,
                                                   Method method,
                                                   @Nullable Object returnValue,
                                                   BeanValidationContext context,
                                                   Set<ConstraintViolation<T>> violations,
                                                   ViolationTarget<T> target,
                                                   ReflectionConstraintDescriptor constraint) {
        if (!isGroupIncluded(constraint, context)) {
            return;
        }
        validateExecutableConstraintDeclaration(constraint, method);
        if (!isTargetedConstraint(constraint, ConstraintTarget.RETURN_VALUE)) {
            return;
        }
        jakarta.validation.Path path = new ReflectionReturnValueExecutablePath(method);
        ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(reflectionClockProvider, object, constraint.getMessageTemplate(), path);
        Boolean valid = validateConstraint(constraint, returnValue, method.getReturnType(), validatorContext, ConstraintTarget.RETURN_VALUE, true);
        if (valid == null) {
            throw unexpectedType(constraint, method.getReturnType());
        }
        validateCustomViolationState(valid, validatorContext);
        addDefaultViolationIfEnabled(valid, validatorContext, violations, target, returnValue, returnValue, constraint, path);
        validateExecutableComposingConstraints(
            object,
            object.getClass(),
            object,
            returnValue,
            method.getReturnType(),
            constraint,
            context,
            violations,
            path,
            ConstraintTarget.RETURN_VALUE,
            method
        );
        addCustomViolations(violations, target, returnValue, returnValue, constraint, validatorContext);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> Set<ConstraintViolation<T>> validateConstructorReturnValueReflectively(Constructor<? extends T> constructor,
                                                                                       T createdObject,
                                                                                       BeanValidationContext context,
                                                                                       BeanValidationContext cascadedContext) {
        warnOnce(constructor.getDeclaringClass().getName(), constructor.getName(), "validating constructor return value without Micronaut executable metadata");
        ReflectionGroupConversions.validateConstructorReturnValueDeclaration(constructor);
        List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(constructor);
        ReturnValueDescriptor providerReturnValueDescriptor = providerConstructorReturnValueDescriptor(constructor);
        boolean cascaded = constructor.isAnnotationPresent(Valid.class)
            || (providerReturnValueDescriptor != null && providerReturnValueDescriptor.isCascaded());
        if (constraints.isEmpty() && !cascaded) {
            return Collections.emptySet();
        }
        Set<ReflectionGroupConversionDescriptor> groupConversions = new LinkedHashSet<>(groupConversionsFor(constructor, constructor.getAnnotatedReturnType()));
        if (providerReturnValueDescriptor != null) {
            groupConversions.addAll(groupConversions(providerReturnValueDescriptor.getGroupConversions()));
        }
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        ViolationTarget<T> target = new ViolationTarget<>(null, constructor.getDeclaringClass(), createdObject);
        for (ReflectionConstraintDescriptor constraint : constraints) {
            if (!isGroupIncluded(constraint, context)) {
                continue;
            }
            validateNonExecutableConstraintDeclaration(constraint);
            if (!isTargetedConstraint(constraint, ConstraintTarget.RETURN_VALUE)) {
                continue;
            }
            jakarta.validation.Path path = new ReflectionConstructorReturnValueExecutablePath(constructor);
            ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(reflectionClockProvider, null, constraint.getMessageTemplate(), path);
            Boolean valid = validateConstraint(constraint, createdObject, constructor.getDeclaringClass(), validatorContext, ConstraintTarget.IMPLICIT, true);
            if (valid == null) {
                throw unexpectedType(constraint, constructor.getDeclaringClass());
            }
            validateCustomViolationState(valid, validatorContext);
            addDefaultViolationIfEnabled(valid, validatorContext, violations, target, createdObject, createdObject, constraint, path);
            addCustomViolations(violations, target, createdObject, createdObject, constraint, validatorContext);
        }
        if (cascaded) {
            BeanValidationContext effectiveCascadedContext = constructor.getDeclaringClass().isInstance(createdObject)
                ? context
                : cascadedContext;
            validateCascadedValueOrContainer(
                null,
                constructor.getDeclaringClass(),
                createdObject,
                createdObject,
                constructor.getDeclaringClass(),
                convertGroups(effectiveCascadedContext, groupConversions),
                violations,
                new ReflectionConstructorReturnValueExecutablePath(constructor)
            );
        }
        return withExecutableReturnValue(violations, createdObject);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> Set<ConstraintViolation<T>> validateConstructorParametersReflectively(Constructor<? extends T> constructor,
                                                                                      Object[] parameterValues,
                                                                                      BeanValidationContext context,
                                                                                      BeanValidationContext cascadedContext) {
        Parameter[] parameters = constructor.getParameters();
        if (parameters.length != parameterValues.length) {
            throw new IllegalArgumentException("The constructor parameter array must have exactly " + parameters.length + " elements.");
        }
        warnOnce(constructor.getDeclaringClass().getName(), constructor.getName(), "validating constructor parameters without Micronaut executable metadata");
        ReflectionGroupConversions.validateConstructorParameterDeclarations(constructor);
        List<String> parameterNames = configuration.getParameterNameProvider().getParameterNames(constructor);
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        ViolationTarget<T> target = new ViolationTarget<>(null, constructor.getDeclaringClass(), null);
        validateConstructorConstraintDeclarations(constructor, context);
        validateConstructorCrossParameterConstraintsReflectively(constructor, parameterValues, context, violations);
        for (int i = 0; i < parameters.length; i++) {
            validateConstructorParameter(constructor, parameters[i], parameterValues[i], i, parameterNames, context, cascadedContext, violations, target);
        }
        return withExecutableParameters(violations, parameterValues);
    }

    private <T> void validateConstructorParameter(Constructor<? extends T> constructor,
                                                 Parameter parameter,
                                                 @Nullable Object value,
                                                 int parameterIndex,
                                                 List<String> parameterNames,
                                                 BeanValidationContext context,
                                                 BeanValidationContext cascadedContext,
                                                 Set<ConstraintViolation<T>> violations,
                                                 ViolationTarget<T> target) {
        List<ReflectionConstraintDescriptor<?>> constraints = parameterConstraints(parameter);
        List<ReflectionContainerElement> containerElements = new ArrayList<>(containerElementsFor(parameter.getAnnotatedType()));
        addAllContainerElements(containerElements, providerConstructorParameterContainerElements(constructor, parameterIndex));
        Set<ReflectionGroupConversionDescriptor> groupConversions = new LinkedHashSet<>(groupConversionsFor(parameter, parameter.getAnnotatedType()));
        groupConversions.addAll(providerConstructorParameterGroupConversions(constructor, parameterIndex));
        if (constraints.isEmpty() && containerElements.isEmpty() && !isCascaded(parameter)) {
            return;
        }
        validateConstructorParameterConstraints(constructor, value, parameterIndex, parameterNames, context, violations, target, constraints);
        validateExecutableContainerElements(
            null,
            constructor.getDeclaringClass(),
            null,
            value,
            constructor.getParameterTypes()[parameterIndex],
            containerElements,
            context,
            violations,
            constructorParameterContainerElementPath(constructor, parameterNames, parameter, parameterIndex)
        );
        if (isCascaded(parameter) && value != null) {
            String parameterName = parameterName(parameterNames, parameter, parameterIndex);
            validateCascadedValueOrContainer(
                null,
                constructor.getDeclaringClass(),
                value,
                value,
                constructor.getParameterTypes()[parameterIndex],
                convertGroups(cascadedContext, groupConversions),
                violations,
                new ReflectionConstructorExecutablePath(constructor, parameterName, parameterIndex)
            );
        }
    }

    private <T> void validateConstructorParameterConstraints(Constructor<? extends T> constructor,
                                                            @Nullable Object value,
                                                            int parameterIndex,
                                                            List<String> parameterNames,
                                                            BeanValidationContext context,
                                                            Set<ConstraintViolation<T>> violations,
                                                            ViolationTarget<T> target,
                                                            List<ReflectionConstraintDescriptor<?>> constraints) {
        for (ReflectionConstraintDescriptor constraint : constraints) {
            if (!isGroupIncluded(constraint, context)) {
                continue;
            }
            jakarta.validation.Path path = new ReflectionConstructorExecutablePath(
                constructor,
                parameterName(parameterNames, constructor.getParameters()[parameterIndex], parameterIndex),
                parameterIndex
            );
            ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(reflectionClockProvider, null, constraint.getMessageTemplate(), path);
            Boolean valid = validateConstraint(constraint, value, constructor.getParameterTypes()[parameterIndex], validatorContext);
            if (valid == null) {
                throw unexpectedType(constraint, constructor.getParameterTypes()[parameterIndex]);
            }
            validateCustomViolationState(valid, validatorContext);
            addDefaultViolationIfEnabled(valid, validatorContext, violations, target, value, value, constraint, path);
            addCustomViolations(violations, target, value, value, constraint, validatorContext);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void validateConstructorCrossParameterConstraintsReflectively(Constructor<? extends T> constructor,
                                                                              Object[] parameterValues,
                                                                              BeanValidationContext context,
                                                                              Set<ConstraintViolation<T>> violations) {
        List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(constructor);
        if (constraints.isEmpty()) {
            return;
        }
        jakarta.validation.Path path = new ReflectionConstructorCrossParameterPath(constructor);
        ViolationTarget<T> target = new ViolationTarget<>(null, constructor.getDeclaringClass(), null);
        for (ReflectionConstraintDescriptor constraint : constraints) {
            if (!isGroupIncluded(constraint, context) || !isTargetedConstraint(constraint, ConstraintTarget.PARAMETERS)) {
                continue;
            }
            ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(
                reflectionClockProvider,
                null,
                constraint.getMessageTemplate(),
                path
            );
            Boolean valid = validateConstraint(
                constraint,
                parameterValues,
                Object[].class,
                validatorContext,
                ConstraintTarget.PARAMETERS,
                true
            );
            if (valid == null) {
                continue;
            }
            validateCustomViolationState(valid, validatorContext);
            addDefaultViolationIfEnabled(valid, validatorContext, violations, target, parameterValues, parameterValues, constraint, path);
            addCustomViolations(violations, target, parameterValues, parameterValues, constraint, validatorContext);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> Set<ConstraintViolation<T>> validateParametersReflectively(T object,
                                                                           Method method,
                                                                           Object[] parameterValues,
                                                                           BeanValidationContext context,
                                                                           BeanValidationContext cascadedContext) {
        Parameter[] parameters = method.getParameters();
        if (parameters.length != parameterValues.length) {
            throw new IllegalArgumentException("The method parameter array must have exactly " + parameters.length + " elements.");
        }
        warnOnce(method.getDeclaringClass().getName(), method.getName(), "validating executable parameters without Micronaut executable metadata");
        ReflectionMethodDeclarations.validateParameterDeclarations(method);
        ReflectionGroupConversions.validateMethodParameterDeclarations(method);
        List<Method> methodHierarchy = ReflectionMethodDeclarations.hierarchy(method);
        List<String> parameterNames = configuration.getParameterNameProvider().getParameterNames(method);
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        ViolationTarget<T> target = new ViolationTarget<>(object, object.getClass(), object);
        validateCrossParameterConstraintsReflectively(object, method, parameterValues, context, parameterNames, violations);
        for (int i = 0; i < parameters.length; i++) {
            validateMethodParameter(object, method, methodHierarchy, parameters[i], parameterValues[i], i, parameterNames, context, cascadedContext, violations, target);
        }
        return withExecutableParameters(violations, parameterValues);
    }

    private <T> void validateMethodParameter(T object,
                                             Method method,
                                             List<Method> methodHierarchy,
                                             Parameter parameter,
                                             @Nullable Object value,
                                             int parameterIndex,
                                             List<String> parameterNames,
                                             BeanValidationContext context,
                                             BeanValidationContext cascadedContext,
                                             Set<ConstraintViolation<T>> violations,
                                             ViolationTarget<T> target) {
        MethodParameterMetadata parameterMetadata = methodParameterMetadata(method, methodHierarchy, parameterIndex);
        if (parameterMetadata.constraints().isEmpty() && parameterMetadata.containerElements().isEmpty() && !parameterMetadata.cascaded()) {
            return;
        }
        validateMethodParameterConstraints(object, method, parameter, value, parameterIndex, parameterNames, context, violations, target, parameterMetadata.constraints());
        validateExecutableContainerElements(
            object,
            object.getClass(),
            object,
            value,
            method.getParameterTypes()[parameterIndex],
            parameterMetadata.containerElements(),
            context,
            violations,
            parameterContainerElementPath(method, parameterNames, parameter, parameterIndex)
        );
        if (parameterMetadata.cascaded() && value != null) {
            String parameterName = parameterName(parameterNames, parameter, parameterIndex);
            validateCascadedValueOrContainer(
                object,
                object.getClass(),
                value,
                value,
                method.getParameterTypes()[parameterIndex],
                convertGroups(cascadedContext, parameterMetadata.groupConversions()),
                violations,
                new ReflectionExecutablePath(method, parameterName, parameterIndex)
            );
        }
    }

    private MethodParameterMetadata methodParameterMetadata(Method method,
                                                           List<Method> methodHierarchy,
                                                           int parameterIndex) {
        boolean ignoreParameterAnnotations = isMethodParameterAnnotationMetadataIgnored(method, parameterIndex);
        List<ReflectionConstraintDescriptor<?>> constraints = new ArrayList<>();
        List<ReflectionContainerElement> containerElements = new ArrayList<>();
        Set<ReflectionGroupConversionDescriptor> groupConversions = new LinkedHashSet<>();
        boolean cascaded = false;
        if (!ignoreParameterAnnotations) {
            for (Method hierarchyMethod : methodHierarchy) {
                Parameter hierarchyParameter = hierarchyMethod.getParameters()[parameterIndex];
                constraints.addAll(parameterConstraints(hierarchyParameter));
                addAllContainerElements(containerElements, containerElementsFor(hierarchyParameter.getAnnotatedType()));
                groupConversions.addAll(groupConversionsFor(hierarchyParameter, hierarchyParameter.getAnnotatedType()));
                cascaded |= isCascaded(hierarchyParameter);
            }
        }
        addAllContainerElements(containerElements, providerParameterContainerElements(method, parameterIndex));
        groupConversions.addAll(providerParameterGroupConversions(method, parameterIndex));
        return new MethodParameterMetadata(constraints, containerElements, cascaded, groupConversions);
    }

    private <T> void validateMethodParameterConstraints(T object,
                                                        Method method,
                                                        Parameter parameter,
                                                        @Nullable Object value,
                                                        int parameterIndex,
                                                        List<String> parameterNames,
                                                        BeanValidationContext context,
                                                        Set<ConstraintViolation<T>> violations,
                                                        ViolationTarget<T> target,
                                                        List<ReflectionConstraintDescriptor<?>> constraints) {
        for (ReflectionConstraintDescriptor constraint : constraints) {
            if (!isGroupIncluded(constraint, context)) {
                continue;
            }
            jakarta.validation.Path path = new ReflectionExecutablePath(method, parameterName(parameterNames, parameter, parameterIndex), parameterIndex);
            ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(reflectionClockProvider, object, constraint.getMessageTemplate(), path);
            Boolean valid = validateConstraint(constraint, value, method.getParameterTypes()[parameterIndex], validatorContext, ConstraintTarget.IMPLICIT, true);
            if (valid == null) {
                throw unexpectedType(constraint, method.getParameterTypes()[parameterIndex]);
            }
            validateCustomViolationState(valid, validatorContext);
            addDefaultViolationIfEnabled(valid, validatorContext, violations, target, value, value, constraint, path);
            addCustomViolations(violations, target, value, value, constraint, validatorContext);
        }
    }

    private record MethodParameterMetadata(List<ReflectionConstraintDescriptor<?>> constraints,
                                           List<ReflectionContainerElement> containerElements,
                                           boolean cascaded,
                                           Set<ReflectionGroupConversionDescriptor> groupConversions) {
    }

    private static Function<ReflectionContainerContext, jakarta.validation.Path> constructorParameterContainerElementPath(Constructor<?> constructor,
                                                                                                                        List<String> parameterNames,
                                                                                                                        Parameter parameter,
                                                                                                                        int parameterIndex) {
        String resolvedName = parameterName(parameterNames, parameter, parameterIndex);
        return containerContext -> new ReflectionConstructorParameterContainerElementPath(constructor, resolvedName, parameterIndex, containerContext);
    }

    private static Function<ReflectionContainerContext, jakarta.validation.Path> parameterContainerElementPath(Method method,
                                                                                                              List<String> parameterNames,
                                                                                                              Parameter parameter,
                                                                                                              int parameterIndex) {
        String resolvedName = parameterName(parameterNames, parameter, parameterIndex);
        return containerContext -> new ReflectionParameterContainerElementPath(method, resolvedName, parameterIndex, containerContext);
    }

    private List<ReflectionContainerElement> providerParameterContainerElements(Method method, int parameterIndex) {
        List<ReflectionContainerElement> containerElements = new ArrayList<>();
        for (Method hierarchyMethod : ReflectionMethodDeclarations.hierarchy(method)) {
            for (MethodDescriptor methodDescriptor : providerMethodDescriptors(hierarchyMethod)) {
                List<ParameterDescriptor> parameterDescriptors = methodDescriptor.getParameterDescriptors();
                if (parameterIndex < parameterDescriptors.size()) {
                    containerElements.addAll(containerElements(parameterDescriptors.get(parameterIndex).getConstrainedContainerElementTypes()));
                }
            }
        }
        return List.copyOf(containerElements);
    }

    private Set<ReflectionGroupConversionDescriptor> providerParameterGroupConversions(Method method, int parameterIndex) {
        Set<ReflectionGroupConversionDescriptor> groupConversions = new LinkedHashSet<>();
        for (Method hierarchyMethod : ReflectionMethodDeclarations.hierarchy(method)) {
            for (MethodDescriptor methodDescriptor : providerMethodDescriptors(hierarchyMethod)) {
                List<ParameterDescriptor> parameterDescriptors = methodDescriptor.getParameterDescriptors();
                if (parameterIndex < parameterDescriptors.size()) {
                    groupConversions.addAll(groupConversions(parameterDescriptors.get(parameterIndex).getGroupConversions()));
                }
            }
        }
        return Set.copyOf(groupConversions);
    }

    private List<ReflectionContainerElement> providerReturnValueContainerElements(Method method) {
        List<ReflectionContainerElement> containerElements = new ArrayList<>();
        for (Method hierarchyMethod : ReflectionMethodDeclarations.hierarchy(method)) {
            for (MethodDescriptor methodDescriptor : providerMethodDescriptors(hierarchyMethod)) {
                containerElements.addAll(containerElements(methodDescriptor.getReturnValueDescriptor().getConstrainedContainerElementTypes()));
            }
        }
        return List.copyOf(containerElements);
    }

    private Set<ReflectionGroupConversionDescriptor> providerReturnValueGroupConversions(Method method) {
        Set<ReflectionGroupConversionDescriptor> groupConversions = new LinkedHashSet<>();
        for (Method hierarchyMethod : ReflectionMethodDeclarations.hierarchy(method)) {
            for (MethodDescriptor methodDescriptor : providerMethodDescriptors(hierarchyMethod)) {
                groupConversions.addAll(groupConversions(methodDescriptor.getReturnValueDescriptor().getGroupConversions()));
            }
        }
        return Set.copyOf(groupConversions);
    }

    private boolean providerReturnValueCascaded(Method method) {
        for (Method hierarchyMethod : ReflectionMethodDeclarations.hierarchy(method)) {
            for (MethodDescriptor methodDescriptor : providerMethodDescriptors(hierarchyMethod)) {
                if (methodDescriptor.getReturnValueDescriptor().isCascaded()) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<MethodDescriptor> providerMethodDescriptors(Method method) {
        List<MethodDescriptor> descriptors = new ArrayList<>();
        for (ValidationMetadataProvider provider : configuration.getMetadataProviders()) {
            if (provider instanceof ReflectionValidationMetadataProvider) {
                continue;
            }
            BeanDescriptor beanDescriptor = provider.getConstraintsForClass(method.getDeclaringClass()).orElse(null);
            if (beanDescriptor == null) {
                continue;
            }
            MethodDescriptor descriptor = beanDescriptor.getConstraintsForMethod(method.getName(), method.getParameterTypes());
            if (descriptor != null) {
                descriptors.add(descriptor);
            }
        }
        return List.copyOf(descriptors);
    }

    private boolean isMethodParameterAnnotationMetadataIgnored(Method method, int parameterIndex) {
        for (Method hierarchyMethod : ReflectionMethodDeclarations.hierarchy(method)) {
            for (ValidationMetadataProvider provider : configuration.getMetadataProviders()) {
                if (provider.isMethodParameterAnnotationMetadataIgnored(
                    hierarchyMethod.getDeclaringClass(),
                    hierarchyMethod.getName(),
                    hierarchyMethod.getParameterTypes(),
                    parameterIndex
                )) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isMethodReturnValueAnnotationMetadataIgnored(Method method) {
        for (Method hierarchyMethod : ReflectionMethodDeclarations.hierarchy(method)) {
            for (ValidationMetadataProvider provider : configuration.getMetadataProviders()) {
                if (provider.isMethodReturnValueAnnotationMetadataIgnored(
                    hierarchyMethod.getDeclaringClass(),
                    hierarchyMethod.getName(),
                    hierarchyMethod.getParameterTypes()
                )) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<ReflectionContainerElement> providerConstructorParameterContainerElements(Constructor<?> constructor, int parameterIndex) {
        List<ReflectionContainerElement> containerElements = new ArrayList<>();
        for (ValidationMetadataProvider provider : configuration.getMetadataProviders()) {
            if (provider instanceof ReflectionValidationMetadataProvider) {
                continue;
            }
            BeanDescriptor beanDescriptor = provider.getConstraintsForClass(constructor.getDeclaringClass()).orElse(null);
            if (beanDescriptor == null) {
                continue;
            }
            ConstructorDescriptor constructorDescriptor = beanDescriptor.getConstraintsForConstructor(constructor.getParameterTypes());
            if (constructorDescriptor == null) {
                continue;
            }
            List<ParameterDescriptor> parameterDescriptors = constructorDescriptor.getParameterDescriptors();
            if (parameterIndex < parameterDescriptors.size()) {
                containerElements.addAll(containerElements(parameterDescriptors.get(parameterIndex).getConstrainedContainerElementTypes()));
            }
        }
        return List.copyOf(containerElements);
    }

    private Set<ReflectionGroupConversionDescriptor> providerConstructorParameterGroupConversions(Constructor<?> constructor, int parameterIndex) {
        Set<ReflectionGroupConversionDescriptor> groupConversions = new LinkedHashSet<>();
        for (ValidationMetadataProvider provider : configuration.getMetadataProviders()) {
            if (provider instanceof ReflectionValidationMetadataProvider) {
                continue;
            }
            BeanDescriptor beanDescriptor = provider.getConstraintsForClass(constructor.getDeclaringClass()).orElse(null);
            if (beanDescriptor == null) {
                continue;
            }
            ConstructorDescriptor constructorDescriptor = beanDescriptor.getConstraintsForConstructor(constructor.getParameterTypes());
            if (constructorDescriptor == null) {
                continue;
            }
            List<ParameterDescriptor> parameterDescriptors = constructorDescriptor.getParameterDescriptors();
            if (parameterIndex < parameterDescriptors.size()) {
                groupConversions.addAll(groupConversions(parameterDescriptors.get(parameterIndex).getGroupConversions()));
            }
        }
        return Set.copyOf(groupConversions);
    }

    private @Nullable ReturnValueDescriptor providerConstructorReturnValueDescriptor(Constructor<?> constructor) {
        for (ValidationMetadataProvider provider : configuration.getMetadataProviders()) {
            if (provider instanceof ReflectionValidationMetadataProvider) {
                continue;
            }
            BeanDescriptor beanDescriptor = provider.getConstraintsForClass(constructor.getDeclaringClass()).orElse(null);
            if (beanDescriptor == null) {
                continue;
            }
            ConstructorDescriptor constructorDescriptor = beanDescriptor.getConstraintsForConstructor(constructor.getParameterTypes());
            if (constructorDescriptor != null) {
                return constructorDescriptor.getReturnValueDescriptor();
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void validateCrossParameterConstraintsReflectively(T object,
                                                                   Method method,
                                                                   Object[] parameterValues,
                                                                   BeanValidationContext context,
                                                                   List<String> parameterNames,
                                                                   Set<ConstraintViolation<T>> violations) {
        List<ReflectionConstraintDescriptor<?>> constraints = ReflectionMethodDeclarations.hierarchy(method)
            .stream()
            .flatMap(hierarchyMethod -> constraintsFor(hierarchyMethod).stream())
            .toList();
        if (constraints.isEmpty()) {
            return;
        }
        jakarta.validation.Path path = new ReflectionMethodCrossParameterPath(method);
        ViolationTarget<T> target = new ViolationTarget<>(object, object.getClass(), object);
        for (ReflectionConstraintDescriptor constraint : constraints) {
            if (!isGroupIncluded(constraint, context)) {
                continue;
            }
            validateExecutableConstraintDeclaration(constraint, method);
            if (!isTargetedConstraint(constraint, ConstraintTarget.PARAMETERS)) {
                continue;
            }
            ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(
                reflectionClockProvider,
                object,
                constraint.getMessageTemplate(),
                path,
                parameterNames
            );
            Boolean valid = validateConstraint(
                constraint,
                parameterValues,
                Object[].class,
                validatorContext,
                ConstraintTarget.PARAMETERS,
                true
            );
            if (valid == null) {
                continue;
            }
            validateCustomViolationState(valid, validatorContext);
            addDefaultViolationIfEnabled(valid, validatorContext, violations, target, parameterValues, parameterValues, constraint, path);
            validateExecutableComposingConstraints(
                object,
                object.getClass(),
                object,
                parameterValues,
                Object[].class,
                constraint,
                context,
                violations,
                path,
                ConstraintTarget.PARAMETERS,
                method
            );
            addCustomViolations(violations, target, parameterValues, parameterValues, constraint, validatorContext);
        }
    }

    private boolean isReachable(@Nullable Object traversableObject,
                                ReflectionProperty property,
                                @Nullable Class<?> rootBeanClass,
                                @Nullable Path pathToTraversableObject) {
        try {
            return reflectionTraversableResolver.isReachable(
                traversableObject,
                new ReflectionNode(property.name),
                rootBeanClass == null ? property.declaringClass() : rootBeanClass,
                pathToTraversableObject == null ? new ReflectionPath(null) : pathToTraversableObject,
                property.elementType()
            );
        } catch (Exception e) {
            throw new ValidationException("Cannot call 'isReachable' on reflectionTraversableResolver: " + reflectionTraversableResolver, e);
        }
    }

    private boolean isCascadable(@Nullable Object traversableObject,
                                 ReflectionProperty property,
                                 @Nullable Class<?> rootBeanClass,
                                 @Nullable Path pathToTraversableObject) {
        try {
            return reflectionTraversableResolver.isCascadable(
                traversableObject,
                new ReflectionNode(property.name),
                rootBeanClass == null ? property.declaringClass() : rootBeanClass,
                pathToTraversableObject == null ? new ReflectionPath(null) : pathToTraversableObject,
                property.elementType()
            );
        } catch (Exception e) {
            throw new ValidationException("Cannot call 'isCascadable' on reflectionTraversableResolver: " + reflectionTraversableResolver, e);
        }
    }

    private <T> void validateProperty(T rootBean,
                                      Object leafBean,
                                      @Nullable Class<?> rootBeanClass,
                                      ReflectionProperty property,
                                      BeanValidationContext context,
                                      BeanValidationContext cascadedContext,
                                      Set<ConstraintViolation<T>> violations,
                                      boolean supplementIntrospection,
                                      boolean validatePropertyConstraints,
                                      boolean validateCascaded,
                                      @Nullable Path pathToLeafBean,
                                      @Nullable Set<String> cascadedProperties) {
        if (!isReachable(leafBean, property, rootBeanClass, pathToLeafBean)) {
            return;
        }
        Object value = property.read(leafBean);
        jakarta.validation.Path propertyPath = pathToLeafBean == null
            ? new ReflectionPath(property.name)
            : new ReflectionAppendedPropertyPath(pathToLeafBean, property.name);
        if (validatePropertyConstraints) {
            validatePropertyConstraints(rootBean, leafBean, property, value, context, violations, supplementIntrospection, propertyPath);
        }
        validateContainerElements(rootBean, rootBean.getClass(), leafBean, property, value, context, violations, shouldSupplementContainerElements(property, supplementIntrospection), null);
        if (validateCascaded
            && value != null
            && property.isCascaded()
            && property.containerElements.stream().noneMatch(ReflectionContainerElement::cascaded)
            && (cascadedProperties == null || cascadedProperties.add(property.name))
            && isCascadable(leafBean, property, rootBeanClass, pathToLeafBean)) {
            validateCascadedValueOrContainer(
                rootBean,
                rootBeanClass,
                value,
                value,
                property.type,
                convertGroups(cascadedContext, property.groupConversions),
                violations,
                propertyPath,
                validatedObjectsIncluding(leafBean)
            );
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void validateConstraints(@Nullable T rootBean,
                                         @Nullable Class<?> rootBeanClass,
                                         @Nullable Object leafBean,
                                         @Nullable Object value,
                                         Class<?> valueType,
                                         List<ReflectionConstraintDescriptor<?>> constraints,
                                         BeanValidationContext context,
                                         Set<ConstraintViolation<T>> violations,
                                         jakarta.validation.Path propertyPath) {
        validateConstraints(rootBean, rootBeanClass, leafBean, value, valueType, constraints, context, violations, propertyPath, true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void validateConstraints(@Nullable T rootBean,
                                         @Nullable Class<?> rootBeanClass,
                                         @Nullable Object leafBean,
                                         @Nullable Object value,
                                         Class<?> valueType,
                                         List<ReflectionConstraintDescriptor<?>> constraints,
                                         BeanValidationContext context,
                                         Set<ConstraintViolation<T>> violations,
                                         jakarta.validation.Path propertyPath,
                                         boolean resolveMostSpecific) {
        for (ReflectionConstraintDescriptor constraint : constraints) {
            if (!isGroupIncluded(constraint, context)) {
                continue;
            }
            validateNonExecutableConstraintDeclaration(constraint);
            ValueExtractorDefinition<Object> unwrappingExtractor = unwrappingExtractor(valueType, constraint);
            if (unwrappingExtractor != null && value != null) {
                extractValues(unwrappingExtractor, value, valueReceiver((nodeName, key, index, iterable, extractedValue) -> {
                    Integer typeArgumentIndex = resolveExtractedTypeArgumentIndex(
                        valueType,
                        unwrappingExtractor.containerType(),
                        unwrappingExtractor.typeArgumentIndex()
                    );
                    jakarta.validation.Path extractedPath = nodeName == null ? propertyPath : new ReflectionNestedContainerElementPath(
                        propertyPath,
                        new ReflectionContainerContext(
                            nodeName,
                            iterable,
                            key,
                            index,
                            valueType,
                            typeArgumentIndex
                        )
                    );
                    validateSingleConstraint(
                        rootBean,
                        rootBeanClass,
                        leafBean,
                        extractedValue,
                        extractedValue == null ? unwrappingExtractor.valueType() : extractedValue.getClass(),
                        constraint,
                        context,
                        violations,
                        extractedPath,
                        resolveMostSpecific
                    );
                }));
                continue;
            }
            validateSingleConstraint(rootBean, rootBeanClass, leafBean, value, valueType, constraint, context, violations, propertyPath, resolveMostSpecific);
        }
    }

    private <T> void validatePropertyConstraints(@Nullable T rootBean,
                                                 @Nullable Object leafBean,
                                                 ReflectionProperty property,
                                                 @Nullable Object value,
                                                 BeanValidationContext context,
                                                 Set<ConstraintViolation<T>> violations,
                                                 boolean supplementIntrospection,
                                                 jakarta.validation.Path propertyPath) {
        List<ReflectionConstraintDescriptor<?>> constraints = supplementIntrospection && rootBean != null
            ? supplementalPropertyConstraints(property.constraints, generatedPropertyConstraints(rootBean.getClass(), property), property.type, context)
            : property.constraints;
        for (ReflectionConstraintDescriptor<?> constraint : constraints) {
            if (!isGroupIncluded(constraint, context)) {
                continue;
            }
            validateNonExecutableConstraintDeclaration(constraint);
            ValueExtractorDefinition<Object> unwrappingExtractor = unwrappingExtractor(property.type, constraint);
            if (unwrappingExtractor == null || value == null) {
                validateSingleConstraint(
                    rootBean,
                    rootBean == null ? null : rootBean.getClass(),
                    leafBean,
                    value,
                    property.type,
                    constraint,
                    context,
                    violations,
                    propertyPath
                );
                continue;
            }
            validateUnwrappedPropertyConstraint(rootBean, leafBean, property, value, context, violations, constraint, unwrappingExtractor);
        }
    }

    private <T> void validateUnwrappedPropertyConstraint(@Nullable T rootBean,
                                                         @Nullable Object leafBean,
                                                         ReflectionProperty property,
                                                         Object value,
                                                         BeanValidationContext context,
                                                         Set<ConstraintViolation<T>> violations,
                                                         ReflectionConstraintDescriptor<?> constraint,
                                                         ValueExtractorDefinition<Object> unwrappingExtractor) {
        extractValues(unwrappingExtractor, value, valueReceiver((nodeName, key, index, iterable, extractedValue) -> {
            Integer typeArgumentIndex = resolveExtractedTypeArgumentIndex(
                property.type,
                unwrappingExtractor.containerType(),
                unwrappingExtractor.typeArgumentIndex()
            );
            jakarta.validation.Path extractedPath = nodeName == null ? new ReflectionPath(property.name) : new ReflectionContainerElementPath(
                property.name,
                new ReflectionContainerContext(
                    nodeName,
                    iterable,
                    key,
                    index,
                    property.type,
                    typeArgumentIndex
                )
            );
            validateSingleConstraint(
                rootBean,
                rootBean == null ? null : rootBean.getClass(),
                leafBean,
                extractedValue,
                extractedValue == null ? unwrappingExtractor.valueType() : extractedValue.getClass(),
                constraint,
                context,
                violations,
                extractedPath
            );
        }));
    }

    @Nullable
    private ValueExtractorDefinition<Object> unwrappingExtractor(Class<?> propertyType,
                                                                 ReflectionConstraintDescriptor<?> constraint) {
        ValidateUnwrappedValue valueUnwrapping = constraint.getValueUnwrapping();
        if (valueUnwrapping == ValidateUnwrappedValue.SKIP) {
            return null;
        }
        List<ValueExtractorDefinition<Object>> valueExtractorDefinitions = reflectionValueExtractorRegistry.findValueExtractors((Class<Object>) propertyType);
        if (valueUnwrapping == ValidateUnwrappedValue.UNWRAP) {
            if (valueExtractorDefinitions.isEmpty()) {
                throw new ConstraintDeclarationException("Cannot unwrap the constraint because no value extractor is present for " + propertyType.getName());
            }
            if (valueExtractorDefinitions.size() > 1) {
                throw new ConstraintDeclarationException("Cannot unwrap the constraint when multiple value extractors are present for " + propertyType.getName());
            }
            return valueExtractorDefinitions.get(0);
        }
        ValueExtractorDefinition<Object> unwrapByDefault = null;
        for (ValueExtractorDefinition<Object> definition : valueExtractorDefinitions) {
            if (definition.unwrapByDefault()) {
                if (unwrapByDefault != null) {
                    throw new ConstraintDeclarationException("Multiple unwrap by default value extractors are present for " + propertyType.getName());
                }
                unwrapByDefault = definition;
            }
        }
        return unwrapByDefault;
    }

    private static void extractValues(ValueExtractorDefinition<Object> definition,
                                      Object value,
                                      ValueExtractor.ValueReceiver receiver) {
        try {
            definition.valueExtractor().extractValues(value, receiver);
        } catch (ValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ValidationException("Cannot extract values from " + definition.containerType().getName(), e);
        }
    }

    private static ValueExtractor.ValueReceiver valueReceiver(ExtractedValueConsumer consumer) {
        return new ValueExtractor.ValueReceiver() {

            @Override
            public void value(@Nullable String nodeName, Object value) {
                consumer.accept(nodeName, null, null, false, value);
            }

            @Override
            public void iterableValue(@Nullable String nodeName, Object value) {
                consumer.accept(nodeName, null, null, true, value);
            }

            @Override
            public void indexedValue(@Nullable String nodeName, int index, Object value) {
                consumer.accept(nodeName, null, index, true, value);
            }

            @Override
            public void keyedValue(@Nullable String nodeName, Object key, Object value) {
                consumer.accept(nodeName, key, null, true, value);
            }
        };
    }

    @FunctionalInterface
    private interface ExtractedValueConsumer {

        void accept(@Nullable String nodeName,
                    @Nullable Object key,
                    @Nullable Integer index,
                    boolean iterable,
                    @Nullable Object value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void validateSingleConstraint(@Nullable T rootBean,
                                              @Nullable Class<?> rootBeanClass,
                                              @Nullable Object leafBean,
                                              @Nullable Object value,
                                              Class<?> valueType,
                                              ReflectionConstraintDescriptor constraint,
                                              BeanValidationContext context,
                                              Set<ConstraintViolation<T>> violations,
                                              jakarta.validation.Path propertyPath) {
        validateSingleConstraint(rootBean, rootBeanClass, leafBean, value, valueType, constraint, context, violations, propertyPath, true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void validateSingleConstraint(@Nullable T rootBean,
                                              @Nullable Class<?> rootBeanClass,
                                              @Nullable Object leafBean,
                                              @Nullable Object value,
                                              Class<?> valueType,
                                              ReflectionConstraintDescriptor constraint,
                                              BeanValidationContext context,
                                              Set<ConstraintViolation<T>> violations,
                                              jakarta.validation.Path propertyPath,
                                              boolean resolveMostSpecific) {
        ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(reflectionClockProvider, rootBean, constraint.getMessageTemplate(), propertyPath);
        Boolean valid = validateConstraint(constraint, value, valueType, validatorContext, ConstraintTarget.IMPLICIT, resolveMostSpecific);
        if (valid == null) {
            if (validateComposingConstraints(rootBean, rootBeanClass, leafBean, propertyPath, value, valueType, constraint, context, violations)) {
                return;
            }
            throw new UnexpectedTypeException("Cannot find a constraint validator for constraint: " + constraint.getType().getName() + " and type: " + valueType);
        }
        validateCustomViolationState(valid, validatorContext);
        if (!valid && !validatorContext.defaultViolationDisabled()) {
            violations.add(new ReflectionConstraintViolation<>(
                rootBean,
                (Class<T>) rootBeanClass,
                leafBean,
                value,
                interpolate(constraint.getMessageTemplate(), constraint, value),
                constraint.getMessageTemplate(),
                propertyPath,
                constraint
            ));
        }
        validateComposingConstraints(rootBean, rootBeanClass, leafBean, propertyPath, value, valueType, constraint, context, violations);
        for (ReflectionConstraintValidatorContext.CustomViolation customViolation : validatorContext.customViolations()) {
            Object invalidValue = customInvalidValue(propertyPath, value);
            violations.add(new ReflectionConstraintViolation<>(
                rootBean,
                (Class<T>) rootBeanClass,
                leafBean,
                invalidValue,
                interpolate(customViolation.messageTemplate(), constraint, invalidValue),
                customViolation.messageTemplate(),
                customViolation.path(),
                constraint
            ));
        }
    }

    private static @Nullable Object customInvalidValue(jakarta.validation.Path propertyPath, @Nullable Object value) {
        Iterator<Path.Node> nodes = propertyPath.iterator();
        if (nodes.hasNext() && nodes.next().getKind() == ElementKind.BEAN && !nodes.hasNext()) {
            return null;
        }
        return value;
    }

    private <T> void addDefaultViolationIfEnabled(boolean valid,
                                                  ReflectionConstraintValidatorContext validatorContext,
                                                  Set<ConstraintViolation<T>> violations,
                                                  ViolationTarget<T> target,
                                                  @Nullable Object invalidValue,
                                                  @Nullable Object interpolationValue,
                                                  ReflectionConstraintDescriptor<?> constraint,
                                                  jakarta.validation.Path path) {
        if (!valid && !validatorContext.defaultViolationDisabled()) {
            addViolation(violations, target, invalidValue, interpolationValue, constraint.getMessageTemplate(), constraint, path);
        }
    }

    private <T> void addCustomViolations(Set<ConstraintViolation<T>> violations,
                                         ViolationTarget<T> target,
                                         @Nullable Object invalidValue,
                                         @Nullable Object interpolationValue,
                                         ReflectionConstraintDescriptor<?> constraint,
                                         ReflectionConstraintValidatorContext validatorContext) {
        for (ReflectionConstraintValidatorContext.CustomViolation customViolation : validatorContext.customViolations()) {
            addViolation(violations, target, invalidValue, interpolationValue, customViolation.messageTemplate(), constraint, customViolation.path());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void addViolation(Set<ConstraintViolation<T>> violations,
                                  ViolationTarget<T> target,
                                  @Nullable Object invalidValue,
                                  @Nullable Object interpolationValue,
                                  String messageTemplate,
                                  ReflectionConstraintDescriptor<?> constraint,
                                  jakarta.validation.Path path) {
        violations.add(new ReflectionConstraintViolation<>(
            target.rootBean(),
            (Class<T>) target.rootBeanClass(),
            target.leafBean(),
            invalidValue,
            interpolate(messageTemplate, constraint, interpolationValue),
            messageTemplate,
            path,
            constraint
        ));
    }

    private <T> void replaceWithSingleViolation(Set<ConstraintViolation<T>> violations,
                                                Set<ConstraintViolation<T>> existingViolations,
                                                ViolationTarget<T> target,
                                                @Nullable Object invalidValue,
                                                @Nullable Object interpolationValue,
                                                ReflectionConstraintDescriptor<?> constraint,
                                                jakarta.validation.Path path) {
        violations.removeIf(violation -> !existingViolations.contains(violation));
        addViolation(violations, target, invalidValue, interpolationValue, constraint.getMessageTemplate(), constraint, path);
    }

    private record ViolationTarget<T>(@Nullable T rootBean,
                                      @Nullable Class<?> rootBeanClass,
                                      @Nullable Object leafBean) {
    }

    private <T> boolean validateComposingConstraints(@Nullable T rootBean,
                                                     @Nullable Class<?> rootBeanClass,
                                                     @Nullable Object leafBean,
                                                     jakarta.validation.Path propertyPath,
                                                     @Nullable Object value,
                                                     Class<?> valueType,
                                                     ReflectionConstraintDescriptor<?> constraint,
                                                     BeanValidationContext context,
                                                     Set<ConstraintViolation<T>> violations) {
        if (constraint.composingConstraints.isEmpty()) {
            return false;
        }
        if (constraint.isReportAsSingleViolation()) {
            Set<ConstraintViolation<T>> existingViolations = new LinkedHashSet<>(violations);
            validateConstraints(rootBean, rootBeanClass, leafBean, value, valueType, constraint.composingConstraints, context, violations, propertyPath);
            if (!existingViolations.containsAll(violations)) {
                replaceWithSingleViolation(violations, existingViolations, new ViolationTarget<>(rootBean, rootBeanClass, leafBean), value, value, constraint, propertyPath);
            }
        } else {
            validateConstraints(rootBean, rootBeanClass, leafBean, value, valueType, constraint.composingConstraints, context, violations, propertyPath);
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void validateExecutableComposingConstraints(@Nullable T rootBean,
                                                            @Nullable Class<?> rootBeanClass,
                                                            @Nullable Object leafBean,
                                                            @Nullable Object value,
                                                            Class<?> valueType,
                                                            ReflectionConstraintDescriptor<?> constraint,
                                                            BeanValidationContext context,
                                                            Set<ConstraintViolation<T>> violations,
                                                            jakarta.validation.Path propertyPath,
                                                            ConstraintTarget constraintTarget,
                                                            Method method) {
        if (constraint.composingConstraints.isEmpty()) {
            return;
        }
        Set<ConstraintViolation<T>> existingViolations = constraint.isReportAsSingleViolation()
            ? new LinkedHashSet<>(violations)
            : Set.of();
        ViolationTarget<T> target = new ViolationTarget<>(rootBean, rootBeanClass, leafBean);
        for (ReflectionConstraintDescriptor composingConstraint : constraint.composingConstraints) {
            if (!isGroupIncluded(composingConstraint, context)) {
                continue;
            }
            validateExecutableConstraintDeclaration(composingConstraint, method);
            if (!appliesTo(composingConstraint, constraintTarget)) {
                continue;
            }
            ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(reflectionClockProvider, rootBean, composingConstraint.getMessageTemplate(), propertyPath);
            Boolean valid = validateConstraint(composingConstraint, value, valueType, validatorContext, constraintTarget, true);
            if (valid == null) {
                validateExecutableComposingConstraints(rootBean, rootBeanClass, leafBean, value, valueType, composingConstraint, context, violations, propertyPath, constraintTarget, method);
                continue;
            }
            validateCustomViolationState(valid, validatorContext);
            addDefaultViolationIfEnabled(valid, validatorContext, violations, target, value, value, composingConstraint, propertyPath);
            validateExecutableComposingConstraints(rootBean, rootBeanClass, leafBean, value, valueType, composingConstraint, context, violations, propertyPath, constraintTarget, method);
            addCustomViolations(violations, target, customInvalidValue(propertyPath, value), value, composingConstraint, validatorContext);
        }
        if (constraint.isReportAsSingleViolation() && !existingViolations.containsAll(violations)) {
            replaceWithSingleViolation(violations, existingViolations, target, value, value, constraint, propertyPath);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void validateContainerElements(@Nullable T rootBean,
                                               @Nullable Class<?> rootBeanClass,
                                               Object leafBean,
                                               ReflectionProperty property,
                                               @Nullable Object containerValue,
                                               BeanValidationContext context,
                                               Set<ConstraintViolation<T>> violations,
                                               boolean supplementIntrospection,
                                               @Nullable Path propertyPathPrefix) {
        if (containerValue == null || property.containerElements.isEmpty()) {
            return;
        }
        for (ReflectionContainerElement containerElement : property.containerElements) {
            if (supplementIntrospection && !isSupplementalContainerElement(containerElement)) {
                continue;
            }
            Class<Object> extractorLookupType = (Class<Object>) property.type;
            if (containerElement.cascaded && containerElement.constraints.isEmpty()) {
                extractorLookupType = (Class<Object>) containerValue.getClass();
            }
            boolean foundExtractor = extractContainerValues(
                extractorLookupType,
                property.type,
                containerValue,
                containerElement,
                (containerContext, value) -> validatePropertyContainerValue(
                    rootBean,
                    rootBeanClass,
                    leafBean,
                    property,
                    propertyPathPrefix,
                    containerElement,
                    context,
                    violations,
                    containerContext,
                    value
                )
            );
            if (!foundExtractor) {
                throw new ConstraintDeclarationException("Cannot validate container element constraints without a value extractor for type argument " + containerElement.typeArgumentIndex + " of " + property.type.getName());
            }
        }
    }

    private <T> void validatePropertyContainerValue(@Nullable T rootBean,
                                                    @Nullable Class<?> rootBeanClass,
                                                    Object leafBean,
                                                    ReflectionProperty property,
                                                    @Nullable Path propertyPathPrefix,
                                                    ReflectionContainerElement containerElement,
                                                    BeanValidationContext context,
                                                    Set<ConstraintViolation<T>> violations,
                                                    ReflectionContainerContext containerContext,
                                                    @Nullable Object value) {
        jakarta.validation.Path containerPath = propertyPathPrefix == null
            ? new ReflectionContainerElementPath(property.name, containerContext)
            : new ReflectionNestedContainerElementPath(propertyPathPrefix, containerContext);
        if (!containerElement.constraints.isEmpty()) {
            validateConstraints(rootBean, rootBeanClass, leafBean, value, containerElement.type, containerElement.constraints, context, violations, containerPath, false);
        }
        if (containerElement.cascaded && value != null) {
            BeanValidationContext cascadedContext = convertGroups(context, containerElement.groupConversions);
            if (propertyPathPrefix == null) {
                validateContainerCascadedValue(rootBean, value, property.name, containerContext, rootBeanClass, cascadedContext, violations);
            } else {
                validateCascadedValue(rootBean, rootBeanClass, value, value, cascadedContext, violations, new ReflectionCascadedContainerElementPath(propertyPathPrefix, containerContext));
            }
        }
        validateNestedContainerElements(rootBean, rootBeanClass, leafBean, value, containerElement.type, containerElement.nestedContainerElements, context, violations, containerPath);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void validateNestedContainerElements(@Nullable T rootBean,
                                                     @Nullable Class<?> rootBeanClass,
                                                     @Nullable Object leafBean,
                                                     @Nullable Object containerValue,
                                                     Class<?> containerType,
                                                     List<ReflectionContainerElement> containerElements,
                                                     BeanValidationContext context,
                                                     Set<ConstraintViolation<T>> violations,
                                                     jakarta.validation.Path parentPath) {
        if (containerValue == null || containerElements.isEmpty()) {
            return;
        }
        for (ReflectionContainerElement containerElement : containerElements) {
            boolean foundExtractor = extractContainerValues(
                containerType,
                containerType,
                containerValue,
                containerElement,
                (containerContext, value) -> validateNestedContainerValue(
                    rootBean,
                    rootBeanClass,
                    leafBean,
                    parentPath,
                    containerElement,
                    context,
                    violations,
                    containerContext,
                    value
                )
            );
            if (!foundExtractor) {
                throw new ConstraintDeclarationException("Cannot validate container element constraints without a value extractor for type argument " + containerElement.typeArgumentIndex + " of " + containerType.getName());
            }
        }
    }

    private <T> void validateNestedContainerValue(@Nullable T rootBean,
                                                  @Nullable Class<?> rootBeanClass,
                                                  @Nullable Object leafBean,
                                                  jakarta.validation.Path parentPath,
                                                  ReflectionContainerElement containerElement,
                                                  BeanValidationContext context,
                                                  Set<ConstraintViolation<T>> violations,
                                                  ReflectionContainerContext containerContext,
                                                  @Nullable Object value) {
        jakarta.validation.Path path = new ReflectionNestedContainerElementPath(parentPath, containerContext);
        validateConstraints(rootBean, rootBeanClass, leafBean, value, containerElement.type, containerElement.constraints, context, violations, path, false);
        if (containerElement.cascaded && value != null) {
            validateCascadedValue(
                rootBean,
                rootBeanClass,
                value,
                value,
                convertGroups(context, containerElement.groupConversions),
                violations,
                new ReflectionCascadedContainerElementPath(parentPath, containerContext)
            );
        }
        validateNestedContainerElements(rootBean, rootBeanClass, leafBean, value, containerElement.type, containerElement.nestedContainerElements, context, violations, path);
    }

    private static boolean isSupplementalContainerElement(ReflectionContainerElement containerElement) {
        return containerElement.providerDeclared
            || containerElement.cascaded
            || containerElement.constraints.stream()
                .anyMatch(constraint -> requiresReflectionValidation(constraint, containerElement.type))
            || containerElement.nestedContainerElements.stream().anyMatch(ReflectionValidator::isSupplementalContainerElement);
    }

    private static boolean shouldSupplementContainerElements(ReflectionProperty property, boolean supplementIntrospection) {
        return supplementIntrospection && property.elementType() == ElementType.FIELD;
    }

    private static boolean isCascaded(Parameter parameter) {
        return parameter.isAnnotationPresent(Valid.class) || parameter.getAnnotatedType().isAnnotationPresent(Valid.class);
    }

    private static boolean isCascaded(Field field) {
        return field.isAnnotationPresent(Valid.class) || field.getAnnotatedType().isAnnotationPresent(Valid.class);
    }

    private static boolean isCascaded(Method method) {
        return method.isAnnotationPresent(Valid.class) || method.getAnnotatedReturnType().isAnnotationPresent(Valid.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void validateExecutableContainerElements(@Nullable T rootBean,
                                                         @Nullable Class<?> rootBeanClass,
                                                         @Nullable Object leafBean,
                                                         @Nullable Object containerValue,
                                                         Class<?> containerType,
                                                         List<ReflectionContainerElement> containerElements,
                                                         BeanValidationContext context,
                                                         Set<ConstraintViolation<T>> violations,
                                                         Function<ReflectionContainerContext, jakarta.validation.Path> pathFactory) {
        if (containerValue == null || containerElements.isEmpty()) {
            return;
        }
        for (ReflectionContainerElement containerElement : containerElements) {
            extractContainerValues(
                containerType,
                containerType,
                containerValue,
                containerElement,
                (containerContext, value) -> validateExecutableContainerValue(
                    rootBean,
                    rootBeanClass,
                    leafBean,
                    containerType,
                    containerElement,
                    context,
                    violations,
                    pathFactory,
                    containerContext,
                    value
                )
            );
        }
    }

    private <T> void validateExecutableContainerValue(@Nullable T rootBean,
                                                      @Nullable Class<?> rootBeanClass,
                                                      @Nullable Object leafBean,
                                                      Class<?> containerType,
                                                      ReflectionContainerElement containerElement,
                                                      BeanValidationContext context,
                                                      Set<ConstraintViolation<T>> violations,
                                                      Function<ReflectionContainerContext, jakarta.validation.Path> pathFactory,
                                                      ReflectionContainerContext containerContext,
                                                      @Nullable Object value) {
        jakarta.validation.Path path = pathFactory.apply(containerContext);
        validateConstraints(rootBean, rootBeanClass, leafBean, value, containerElement.type, containerElement.constraints, context, violations, path);
        if (containerElement.cascaded && value != null) {
            validateCascadedValue(
                rootBean,
                rootBeanClass,
                value,
                value,
                convertGroups(context, containerElement.groupConversions),
                violations,
                new ReflectionCascadedContainerElementPath(pathFactory.apply(new ReflectionContainerContext(null, false, null, null, containerType, null)), containerContext)
            );
        }
        validateNestedContainerElements(rootBean, rootBeanClass, leafBean, value, containerElement.type, containerElement.nestedContainerElements, context, violations, path);
    }

    @SuppressWarnings("unchecked")
    private boolean extractContainerValues(Class<?> extractorLookupType,
                                           Class<?> contextContainerType,
                                           Object containerValue,
                                           ReflectionContainerElement containerElement,
                                           ContainerValueConsumer consumer) {
        boolean foundExtractor = false;
        for (ValueExtractorDefinition<Object> valueExtractorDefinition : reflectionValueExtractorRegistry.findValueExtractors((Class<Object>) extractorLookupType)) {
            if (!Objects.equals(valueExtractorDefinition.typeArgumentIndex(), containerElement.typeArgumentIndex)) {
                continue;
            }
            foundExtractor = true;
            extractValues(valueExtractorDefinition, containerValue, valueReceiver((nodeName, key, index, iterable, value) -> {
                ReflectionContainerContext containerContext = new ReflectionContainerContext(
                    nodeName,
                    iterable,
                    key,
                    index,
                    contextContainerType,
                    valueExtractorDefinition.typeArgumentIndex()
                );
                consumer.accept(containerContext, value);
            }));
        }
        return foundExtractor;
    }

    @FunctionalInterface
    private interface ContainerValueConsumer {

        void accept(ReflectionContainerContext containerContext, @Nullable Object value);
    }

    private <T> void validateContainerCascadedValue(@Nullable T rootBean,
                                                    Object value,
                                                    String propertyName,
                                                    ReflectionContainerContext containerContext,
                                                    @Nullable Class<?> rootBeanClass,
                                                    BeanValidationContext context,
                                                    Set<ConstraintViolation<T>> violations) {
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(value.getClass());
        validateConstraints(
            rootBean,
            rootBeanClass,
            value,
            value,
            value.getClass(),
            metadata.constraints,
            context,
            violations,
            new ReflectionCascadedContainerElementPath(
                new ReflectionContainerElementPath(
                    propertyName,
                    new ReflectionContainerContext(null, false, null, null, containerContext.containerClass(), null)
                ),
                containerContext
            )
        );
        Set<String> cascadedProperties = new LinkedHashSet<>();
        for (List<ReflectionProperty> properties : metadata.properties.values()) {
            for (ReflectionProperty property : properties) {
                Object propertyValue = property.read(value);
                validateConstraints(
                    rootBean,
                    rootBeanClass,
                    value,
                    propertyValue,
                    property.type,
                    property.constraints,
                    context,
                    violations,
                    new ReflectionContainerPropertyPath(propertyName, property.name, containerContext)
                );
            }
        }
        for (ReflectionProperty property : providerOnlyProperties(value.getClass(), metadata.properties.keySet())) {
            Object propertyValue = property.read(value);
            validateConstraints(
                rootBean,
                rootBeanClass,
                value,
                propertyValue,
                property.type,
                property.constraints,
                context,
                violations,
                new ReflectionContainerPropertyPath(propertyName, property.name, containerContext)
            );
            validateContainerElements(rootBean, rootBeanClass, value, property, propertyValue, context, violations, false, null);
        }
    }

    private <T> void validateCascadedValue(@Nullable T rootBean,
                                           @Nullable Class<?> rootBeanClass,
                                           @Nullable Object leafBean,
                                           @Nullable Object value,
                                           BeanValidationContext context,
                                           Set<ConstraintViolation<T>> violations,
                                           jakarta.validation.Path beanPath) {
        validateCascadedValue(rootBean, rootBeanClass, leafBean, value, context, violations, beanPath, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private <T> void validateCascadedValueOrContainer(@Nullable T rootBean,
                                                      @Nullable Class<?> rootBeanClass,
                                                      @Nullable Object leafBean,
                                                      @Nullable Object value,
                                                      Class<?> declaredType,
                                                      BeanValidationContext context,
                                                      Set<ConstraintViolation<T>> violations,
                                                      jakarta.validation.Path beanPath) {
        validateCascadedValueOrContainer(rootBean, rootBeanClass, leafBean, value, declaredType, context, violations, beanPath, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Set<Object> validatedObjectsIncluding(@Nullable Object value) {
        Set<Object> validatedObjects = Collections.newSetFromMap(new IdentityHashMap<>());
        if (value != null) {
            validatedObjects.add(value);
        }
        return validatedObjects;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void validateCascadedValueOrContainer(@Nullable T rootBean,
                                                      @Nullable Class<?> rootBeanClass,
                                                      @Nullable Object leafBean,
                                                      @Nullable Object value,
                                                      Class<?> declaredType,
                                                      BeanValidationContext context,
                                                      Set<ConstraintViolation<T>> violations,
                                                      jakarta.validation.Path beanPath,
                                                      Set<Object> validatedObjects) {
        if (value == null) {
            return;
        }
        if (declaredType.isArray() || value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object element = Array.get(value, i);
                if (element == null) {
                    continue;
                }
                ReflectionContainerContext containerContext = new ReflectionContainerContext(
                    null,
                    true,
                    null,
                    i,
                    Object[].class,
                    null
                );
                validateCascadedValue(
                    rootBean,
                    rootBeanClass,
                    element,
                    element,
                    context,
                    violations,
                    new ReflectionCascadedContainerElementPath(beanPath, containerContext),
                    validatedObjects
                );
            }
            return;
        }
        List<ValueExtractorDefinition<Object>> valueExtractorDefinitions = reflectionValueExtractorRegistry.findValueExtractors((Class<Object>) declaredType);
        boolean container = false;
        for (ValueExtractorDefinition<Object> valueExtractorDefinition : valueExtractorDefinitions) {
            if (!shouldCascadeContainerValue(declaredType, valueExtractorDefinition)) {
                continue;
            }
            container = true;
            extractValues(valueExtractorDefinition, value, new ValueExtractor.ValueReceiver() {

                @Override
                public void value(String nodeName, Object value) {
                    validateContainerValue(nodeName, null, null, false, value);
                }

                @Override
                public void iterableValue(String nodeName, Object value) {
                    validateContainerValue(nodeName, null, null, true, value);
                }

                @Override
                public void indexedValue(String nodeName, int index, Object value) {
                    validateContainerValue(nodeName, null, index, true, value);
                }

                @Override
                public void keyedValue(String nodeName, Object key, Object value) {
                    validateContainerValue(nodeName, key, null, true, value);
                }

                private void validateContainerValue(@Nullable String nodeName,
                                                    @Nullable Object key,
                                                    @Nullable Integer index,
                                                    boolean iterable,
                                                    @Nullable Object extractedValue) {
                    if (extractedValue == null) {
                        return;
                    }
                    ReflectionContainerContext containerContext = new ReflectionContainerContext(
                        null,
                        iterable,
                        key,
                        index,
                        declaredType,
                        legacyCascadedTypeArgumentIndex(declaredType, valueExtractorDefinition)
                    );
                    validateCascadedValue(
                        rootBean,
                        rootBeanClass,
                        extractedValue,
                        extractedValue,
                        context,
                        violations,
                        new ReflectionCascadedContainerElementPath(beanPath, containerContext),
                        validatedObjects
                    );
                }
            });
        }
        if (!container) {
            validateCascadedValue(rootBean, rootBeanClass, leafBean, value, context, violations, beanPath, validatedObjects);
        }
    }

    private static @Nullable Integer legacyCascadedTypeArgumentIndex(Class<?> declaredType,
                                                                     ValueExtractorDefinition<?> valueExtractorDefinition) {
        return declaredType.getTypeParameters().length == 0 ? null : valueExtractorDefinition.typeArgumentIndex();
    }

    private static boolean shouldCascadeContainerValue(Class<?> declaredType,
                                                       ValueExtractorDefinition<?> valueExtractorDefinition) {
        return !Map.class.isAssignableFrom(declaredType)
            || Objects.equals(valueExtractorDefinition.typeArgumentIndex(), 1);
    }

    private <T> void validateCascadedValue(@Nullable T rootBean,
                                           @Nullable Class<?> rootBeanClass,
                                           @Nullable Object leafBean,
                                           @Nullable Object value,
                                           BeanValidationContext context,
                                           Set<ConstraintViolation<T>> violations,
                                           jakarta.validation.Path beanPath,
                                           Set<Object> validatedObjects) {
        if (value == null) {
            return;
        }
        if (!validatedObjects.add(value)) {
            return;
        }
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(value.getClass(), configuration.getMetadataProviders());
        try {
            for (List<Class<?>> groupPass : ReflectionGroupSequences.validationGroupPasses(value.getClass(), context, configuration.getMetadataProviders())) {
                int violationCount = violations.size();
                BeanValidationContext groupContext = BeanValidationContext.fromGroups(groupPass.toArray(Class<?>[]::new));
                validateCascadedValuePass(rootBean, rootBeanClass, leafBean, value, groupContext, violations, beanPath, metadata, validatedObjects);
                if (violations.size() > violationCount) {
                    break;
                }
            }
        } finally {
            validatedObjects.remove(value);
        }
    }

    private <T> void validateCascadedValuePass(@Nullable T rootBean,
                                               @Nullable Class<?> rootBeanClass,
                                               @Nullable Object leafBean,
                                               Object value,
                                               BeanValidationContext context,
                                               Set<ConstraintViolation<T>> violations,
                                               jakarta.validation.Path beanPath,
                                               ReflectionBeanMetadata metadata,
                                               Set<Object> validatedObjects) {
        validateConstraints(
            rootBean,
            rootBeanClass,
            leafBean,
            value,
            value.getClass(),
            metadata.constraints,
            context,
            violations,
            beanPath
        );
        Set<String> cascadedProperties = new LinkedHashSet<>();
        for (List<ReflectionProperty> properties : metadata.properties.values()) {
            for (ReflectionProperty property : properties) {
                validateCascadedProperty(rootBean, rootBeanClass, value, property, context, convertGroups(context, property.groupConversions), violations, beanPath, cascadedProperties, validatedObjects);
            }
        }
        for (ReflectionProperty property : providerOnlyProperties(value.getClass(), metadata.properties.keySet())) {
            validateCascadedProperty(rootBean, rootBeanClass, value, property, context, context, violations, beanPath, cascadedProperties, validatedObjects);
        }
    }

    private <T> void validateCascadedProperty(@Nullable T rootBean,
                                              @Nullable Class<?> rootBeanClass,
                                              Object bean,
                                              ReflectionProperty property,
                                              BeanValidationContext context,
                                              BeanValidationContext cascadedContext,
                                              Set<ConstraintViolation<T>> violations,
                                              jakarta.validation.Path beanPath,
                                              Set<String> cascadedProperties,
                                              Set<Object> validatedObjects) {
        if (!isReachable(bean, property, rootBeanClass, beanPath)) {
            return;
        }
        Object propertyValue = property.read(bean);
        jakarta.validation.Path propertyPath = new ReflectionAppendedPropertyPath(beanPath, property.name);
        validateConstraints(rootBean, rootBeanClass, bean, propertyValue, property.type, property.constraints, context, violations, propertyPath);
        validateContainerElements(rootBean, rootBeanClass, bean, property, propertyValue, context, violations, false, propertyPath);
        if (propertyValue != null
            && property.isCascaded()
            && cascadedProperties.add(property.name)
            && isCascadable(bean, property, rootBeanClass, beanPath)) {
            validateCascadedValueOrContainer(
                rootBean,
                rootBeanClass,
                propertyValue,
                propertyValue,
                property.type,
                cascadedContext,
                violations,
                propertyPath,
                validatedObjects
            );
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private @Nullable Boolean validateConstraint(ReflectionConstraintDescriptor constraint,
                                       @Nullable Object value,
                                       Class<?> valueType,
                                       ReflectionConstraintValidatorContext validatorContext) {
        return validateConstraint(constraint, value, valueType, validatorContext, ConstraintTarget.IMPLICIT);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private @Nullable Boolean validateConstraint(ReflectionConstraintDescriptor constraint,
                                       @Nullable Object value,
                                       Class<?> valueType,
                                       ReflectionConstraintValidatorContext validatorContext,
                                       ConstraintTarget constraintTarget) {
        return validateConstraint(constraint, value, valueType, validatorContext, constraintTarget, false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private @Nullable Boolean validateConstraint(ReflectionConstraintDescriptor constraint,
                                       @Nullable Object value,
                                       Class<?> valueType,
                                       ReflectionConstraintValidatorContext validatorContext,
                                       ConstraintTarget constraintTarget,
                                       boolean resolveMostSpecific) {
        List<Class<? extends jakarta.validation.ConstraintValidator<?, ?>>> validatorClasses = constraint.getConstraintValidatorClasses();
        if (resolveMostSpecific) {
            validatorClasses = mostSpecificValidatorClass(constraint, valueType, constraintTarget, validatorClasses);
        }
        Boolean jakartaResult = validateJakartaConstraintValidators(constraint, value, valueType, validatorContext, constraintTarget, validatorClasses);
        if (jakartaResult != null) {
            return jakartaResult;
        }
        Boolean micronautResult = validateMicronautConstraintValidator(constraint, value, valueType, validatorContext);
        return micronautResult == null ? validateMinMaxCharSequence(constraint, value, valueType) : micronautResult;
    }

    private static List<Class<? extends jakarta.validation.ConstraintValidator<?, ?>>> mostSpecificValidatorClass(
        ReflectionConstraintDescriptor<?> constraint,
        Class<?> valueType,
        ConstraintTarget constraintTarget,
        List<Class<? extends jakarta.validation.ConstraintValidator<?, ?>>> validatorClasses) {
        Class<? extends jakarta.validation.ConstraintValidator<?, ?>> validatorClass = ReflectionConstraintValidatorResolution.resolve(
            constraint.getType(),
            validatorClasses,
            valueType,
            constraintTarget
        );
        return validatorClass == null ? List.of() : List.of(validatorClass);
    }

    private @Nullable Boolean validateJakartaConstraintValidators(
        ReflectionConstraintDescriptor constraint,
        @Nullable Object value,
        Class<?> valueType,
        ReflectionConstraintValidatorContext validatorContext,
        ConstraintTarget constraintTarget,
        List<Class<? extends jakarta.validation.ConstraintValidator<?, ?>>> validatorClasses) {
        jakarta.validation.ConstraintValidatorFactory constraintValidatorFactory = configuration.getConstraintValidatorFactory();
        for (Object validatorClass : validatorClasses) {
            Class<? extends jakarta.validation.ConstraintValidator> validatorType = (Class<? extends jakarta.validation.ConstraintValidator>) validatorClass;
            jakarta.validation.ConstraintValidator validator = constraintValidatorFactory instanceof InternalConstraintValidatorFactory internalFactory
                ? internalFactory.getInstance(validatorType, valueType, constraintTarget)
                : constraintValidatorFactory.getInstance(validatorType);
            if (validator == null) {
                continue;
            }
            try {
                validator.initialize(constraint.getAnnotation());
                return validator.isValid(value, validatorContext);
            } catch (ValidationException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ValidationException("Cannot validate constraint " + constraint.getType().getName(), e);
            } finally {
                constraintValidatorFactory.releaseInstance(validator);
            }
        }
        return null;
    }

    private @Nullable Boolean validateMicronautConstraintValidator(ReflectionConstraintDescriptor constraint,
                                                                  @Nullable Object value,
                                                                  Class<?> valueType,
                                                                  ReflectionConstraintValidatorContext validatorContext) {
        Optional<ConstraintValidator<Annotation, Object>> validator = (Optional) configuration.getConstraintValidatorRegistry()
            .findConstraintValidator(constraint.getType(), ReflectionUtils.getWrapperType(valueType));
        if (validator.isPresent()) {
            try {
                return validator.get().isValid(value, constraint.annotationValue, validatorContext);
            } catch (ValidationException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ValidationException("Cannot validate constraint " + constraint.getType().getName(), e);
            }
        }
        return null;
    }

    private static void validateCustomViolationState(boolean valid,
                                                     ReflectionConstraintValidatorContext validatorContext) {
        if (!valid && validatorContext.defaultViolationDisabled() && validatorContext.customViolations().isEmpty()) {
            throw new ValidationException("Default violation is disabled and no violations were added");
        }
    }

    private @Nullable Boolean validateMinMaxCharSequence(ReflectionConstraintDescriptor<?> constraint,
                                                         @Nullable Object value,
                                                         Class<?> valueType) {
        if (!CharSequence.class.isAssignableFrom(valueType) && !Number.class.isAssignableFrom(valueType)) {
            return null;
        }
        Class<?> constraintType = constraint.getType();
        if (constraintType != Min.class && constraintType != Max.class) {
            return null;
        }
        if (value == null) {
            return true;
        }
        BigDecimal number;
        try {
            number = value instanceof Number numericValue
                ? new BigDecimal(numericValue.toString())
                : new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return false;
        }
        long limit = (Long) constraint.getAttributes().get(MEMBER_VALUE);
        int comparison = number.compareTo(BigDecimal.valueOf(limit));
        return constraintType == Min.class ? comparison >= 0 : comparison <= 0;
    }

    private String interpolate(String template, ReflectionConstraintDescriptor<?> descriptor, @Nullable Object value) {
        return reflectionMessageInterpolator.interpolate(template, new InterpolationContext(descriptor, value));
    }

    private static boolean isGroupIncluded(ReflectionConstraintDescriptor<?> descriptor, BeanValidationContext context) {
        List<Class<?>> groups = context.groups();
        Set<Class<?>> descriptorGroups = descriptor.getGroups();
        if (groups.isEmpty()) {
            return descriptorGroups.contains(jakarta.validation.groups.Default.class);
        }
        if (descriptor.matchesImplicitGroup(context)) {
            return true;
        }
        return groups.stream().anyMatch(group -> descriptorGroups.contains(group)
            || descriptorGroups.contains(jakarta.validation.groups.Default.class) && group.getDeclaredAnnotation(GroupSequence.class) != null);
    }

    private static BeanValidationContext convertGroups(BeanValidationContext context,
                                                       Set<ReflectionGroupConversionDescriptor> groupConversions) {
        if (groupConversions.isEmpty()) {
            return context;
        }
        List<Class<?>> sourceGroups = context.groups().isEmpty()
            ? List.of(jakarta.validation.groups.Default.class)
            : context.groups();
        List<Class<?>> convertedGroups = new ArrayList<>(sourceGroups.size());
        for (Class<?> sourceGroup : sourceGroups) {
            List<Class<?>> expandedGroups = expandGroup(sourceGroup);
            List<Class<?>> convertedExpandedGroups = new ArrayList<>(expandedGroups.size());
            boolean converted = false;
            for (Class<?> expandedGroup : expandedGroups) {
                Class<?> convertedGroup = expandedGroup;
                for (ReflectionGroupConversionDescriptor groupConversion : groupConversions) {
                    if (groupConversion.from == expandedGroup) {
                        convertedGroup = groupConversion.to;
                        converted = true;
                        break;
                    }
                }
                convertedExpandedGroups.add(convertedGroup);
            }
            if (converted) {
                convertedGroups.addAll(convertedExpandedGroups.stream()
                    .filter(group -> group != sourceGroup)
                    .toList());
            } else {
                convertedGroups.add(sourceGroup);
            }
        }
        return BeanValidationContext.fromGroups(convertedGroups.toArray(Class<?>[]::new));
    }

    private static List<Class<?>> expandGroup(Class<?> group) {
        List<Class<?>> groups = new ArrayList<>();
        addInheritedGroup(group, groups);
        return groups;
    }

    private static void addInheritedGroup(Class<?> group, List<Class<?>> groups) {
        if (!groups.contains(group)) {
            groups.add(group);
        }
        for (Class<?> inheritedGroup : group.getInterfaces()) {
            addInheritedGroup(inheritedGroup, groups);
        }
    }

    private static boolean appliesTo(ReflectionConstraintDescriptor<?> descriptor, ConstraintTarget target) {
        ConstraintTarget validationAppliesTo = descriptor.getValidationAppliesTo();
        return validationAppliesTo == null || validationAppliesTo == ConstraintTarget.IMPLICIT || validationAppliesTo == target;
    }

    private static void validateNonExecutableConstraintDeclaration(ReflectionConstraintDescriptor<?> descriptor) {
        if (descriptor.hasValidationAppliesTo() && descriptor.getValidationAppliesTo() != ConstraintTarget.IMPLICIT) {
            throw new ConstraintDeclarationException("validationAppliesTo is only allowed on executable constraints");
        }
    }

    private static void validateExecutableConstraintDeclaration(ReflectionConstraintDescriptor<?> descriptor, Method method) {
        if (!descriptor.hasValidationAppliesTo()) {
            return;
        }
        ConstraintTarget validationAppliesTo = descriptor.getValidationAppliesTo();
        if (validationAppliesTo == ConstraintTarget.PARAMETERS && method.getParameterCount() == 0) {
            throw new ConstraintDeclarationException("ConstraintTarget.PARAMETERS requires executable parameters");
        }
        if (validationAppliesTo == ConstraintTarget.RETURN_VALUE && method.getReturnType() == Void.TYPE) {
            throw new ConstraintDeclarationException("ConstraintTarget.RETURN_VALUE requires a method return value");
        }
        if (validationAppliesTo == ConstraintTarget.IMPLICIT && method.getParameterCount() > 0 && method.getReturnType() != Void.TYPE) {
            throw new ConstraintDeclarationException("ConstraintTarget.IMPLICIT is ambiguous for methods with parameters and a return value");
        }
    }

    private static void validateConstructorConstraintDeclarations(Constructor<?> constructor, BeanValidationContext context) {
        for (ReflectionConstraintDescriptor<?> constraint : constraintsFor(constructor)) {
            if (!isGroupIncluded(constraint, context) || !constraint.hasValidationAppliesTo()) {
                continue;
            }
            ConstraintTarget validationAppliesTo = constraint.getValidationAppliesTo();
            if (validationAppliesTo == ConstraintTarget.PARAMETERS && constructor.getParameterCount() == 0) {
                throw new ConstraintDeclarationException("ConstraintTarget.PARAMETERS requires constructor parameters");
            }
            if (validationAppliesTo == ConstraintTarget.IMPLICIT && constructor.getParameterCount() > 0) {
                throw new ConstraintDeclarationException("ConstraintTarget.IMPLICIT is ambiguous for constructors with parameters");
            }
        }
    }

    private void warnOnce(String type, String member, String reason) {
        if (warningsEnabled && WARNED_REFLECTION_ACCESS.putIfAbsent(type + "#" + member + "#" + reason, Boolean.TRUE) == null) {
            LOG.warn("Micronaut Validation is using reflection fallback for {} {}: {}", type, member, reason);
        }
    }

    private static <T> T requireNonNull(String name, @Nullable T value) {
        if (value == null) {
            throw new IllegalArgumentException("Argument [" + name + "] cannot be null");
        }
        return value;
    }

    private static String requireNonEmpty(String name, @Nullable String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Argument [" + name + "] cannot be empty");
        }
        return value;
    }

    private static String parameterName(List<String> parameterNames, Parameter parameter, int index) {
        if (parameterNames.size() > index) {
            return parameterNames.get(index);
        }
        return parameter.getName();
    }

    private static List<ReflectionConstraintDescriptor<?>> constraintsFor(AnnotatedElement element) {
        return constraintsFor(element, null);
    }

    private static List<ReflectionConstraintDescriptor<?>> parameterConstraints(Parameter parameter) {
        List<ReflectionConstraintDescriptor<?>> constraints = new ArrayList<>(constraintsFor(parameter));
        for (ReflectionConstraintDescriptor<?> typeUseConstraint : constraintsFor(parameter.getAnnotatedType())) {
            boolean duplicate = constraints.stream()
                .anyMatch(constraint -> constraint.getAnnotation().equals(typeUseConstraint.getAnnotation()));
            if (!duplicate) {
                constraints.add(typeUseConstraint);
            }
        }
        return constraints;
    }

    private static List<ReflectionConstraintDescriptor<?>> constraintsFor(AnnotatedElement element, @Nullable Class<?> implicitGroup) {
        return constraintsFor(element, implicitGroup, List.of());
    }

    private static List<ReflectionConstraintDescriptor<?>> constraintsFor(AnnotatedElement element,
                                                                          @Nullable Class<?> implicitGroup,
                                                                          List<ValidationMetadataProvider> metadataProviders) {
        List<ReflectionConstraintDescriptor<?>> constraints = new ArrayList<>();
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType.isAnnotationPresent(Constraint.class)) {
                constraints.add(new ReflectionConstraintDescriptor<>(annotation, implicitGroup, metadataProviders));
            } else {
                constraints.addAll(containedConstraints(annotation, implicitGroup, metadataProviders));
            }
        }
        return constraints;
    }

    private static List<ReflectionContainerElement> containerElementsFor(AnnotatedType type) {
        if (!(type instanceof AnnotatedParameterizedType parameterizedType)) {
            return List.of();
        }
        AnnotatedType[] typeArguments = parameterizedType.getAnnotatedActualTypeArguments();
        List<ReflectionContainerElement> containerElements = new ArrayList<>();
        for (int i = 0; i < typeArguments.length; i++) {
            AnnotatedType typeArgument = typeArguments[i];
            List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(typeArgument);
            boolean cascaded = typeArgument.isAnnotationPresent(Valid.class);
            List<ReflectionContainerElement> nestedContainerElements = containerElementsFor(typeArgument);
            if (!constraints.isEmpty() || cascaded || !nestedContainerElements.isEmpty()) {
                containerElements.add(new ReflectionContainerElement(
                    getClassFromType(type.getType()),
                    i,
                    getClassFromType(typeArgument.getType()),
                    constraints,
                    cascaded,
                    groupConversionsFor(typeArgument),
                    nestedContainerElements,
                    false
                ));
            }
        }
        return List.copyOf(containerElements);
    }

    private static Set<ReflectionGroupConversionDescriptor> groupConversionsFor(AnnotatedType type) {
        Set<ReflectionGroupConversionDescriptor> groupConversions = new LinkedHashSet<>();
        collectGroupConversions(type, groupConversions);
        return Set.copyOf(groupConversions);
    }

    private static Set<ReflectionGroupConversionDescriptor> groupConversionsFor(AnnotatedElement element, AnnotatedType type) {
        Set<ReflectionGroupConversionDescriptor> groupConversions = new LinkedHashSet<>();
        collectGroupConversions(element, groupConversions);
        collectGroupConversions(type, groupConversions);
        return Set.copyOf(groupConversions);
    }

    private static void collectGroupConversions(AnnotatedElement element, Set<ReflectionGroupConversionDescriptor> groupConversions) {
        ConvertGroup convertGroup = element.getAnnotation(ConvertGroup.class);
        if (convertGroup != null) {
            groupConversions.add(new ReflectionGroupConversionDescriptor(convertGroup.from(), convertGroup.to()));
        }
        ConvertGroup.List convertGroups = element.getAnnotation(ConvertGroup.List.class);
        if (convertGroups != null) {
            for (ConvertGroup listedConvertGroup : convertGroups.value()) {
                groupConversions.add(new ReflectionGroupConversionDescriptor(listedConvertGroup.from(), listedConvertGroup.to()));
            }
        }
    }

    private static Class<?> getClassFromType(Type type) {
        if (type instanceof Class<?> classType) {
            return classType;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return getClassFromType(parameterizedType.getRawType());
        }
        if (type instanceof WildcardType wildcardType) {
            return getClassFromType(wildcardType.getUpperBounds()[0]);
        }
        return Object.class;
    }

    private static @Nullable Integer resolveExtractedTypeArgumentIndex(Class<?> declaredType,
                                                                       Class<?> extractorContainerType,
                                                                       @Nullable Integer extractorTypeArgumentIndex) {
        if (extractorTypeArgumentIndex == null || declaredType == extractorContainerType) {
            return extractorTypeArgumentIndex;
        }
        Integer resolved = resolveExtractedTypeArgumentIndex(declaredType, declaredType.getGenericSuperclass(), extractorContainerType, extractorTypeArgumentIndex);
        if (resolved != null) {
            return resolved;
        }
        for (Type genericInterface : declaredType.getGenericInterfaces()) {
            resolved = resolveExtractedTypeArgumentIndex(declaredType, genericInterface, extractorContainerType, extractorTypeArgumentIndex);
            if (resolved != null) {
                return resolved;
            }
        }
        return extractorTypeArgumentIndex;
    }

    private static @Nullable Integer resolveExtractedTypeArgumentIndex(Class<?> declaredType,
                                                                       @Nullable Type genericType,
                                                                       Class<?> extractorContainerType,
                                                                       int extractorTypeArgumentIndex) {
        if (!(genericType instanceof ParameterizedType parameterizedType) || parameterizedType.getRawType() != extractorContainerType) {
            return null;
        }
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        if (extractorTypeArgumentIndex >= actualTypeArguments.length) {
            return null;
        }
        Type actualTypeArgument = actualTypeArguments[extractorTypeArgumentIndex];
        if (actualTypeArgument instanceof TypeVariable<?> typeVariable) {
            TypeVariable<?>[] declaredTypeParameters = declaredType.getTypeParameters();
            for (int i = 0; i < declaredTypeParameters.length; i++) {
                if (Objects.equals(declaredTypeParameters[i].getName(), typeVariable.getName())) {
                    return i;
                }
            }
        }
        return extractorTypeArgumentIndex;
    }

    private static List<ReflectionConstraintDescriptor<?>> containedConstraints(Annotation container) {
        return containedConstraints(container, null);
    }

    private static List<ReflectionConstraintDescriptor<?>> containedConstraints(Annotation container, @Nullable Class<?> implicitGroup) {
        return containedConstraints(container, implicitGroup, List.of());
    }

    private static List<ReflectionConstraintDescriptor<?>> containedConstraints(Annotation container,
                                                                               @Nullable Class<?> implicitGroup,
                                                                               List<ValidationMetadataProvider> metadataProviders) {
        try {
            Method valueMethod = container.annotationType().getDeclaredMethod(MEMBER_VALUE);
            if (!valueMethod.getReturnType().isArray() || !Annotation.class.isAssignableFrom(valueMethod.getReturnType().getComponentType())) {
                return List.of();
            }
            valueMethod.setAccessible(true);
            Annotation[] annotations = (Annotation[]) valueMethod.invoke(container);
            return Arrays.stream(annotations)
                .filter(annotation -> annotation.annotationType().isAnnotationPresent(Constraint.class))
                .map(annotation -> new ReflectionConstraintDescriptor<>(annotation, implicitGroup, metadataProviders))
                .collect(Collectors.toCollection(ArrayList::new));
        } catch (NoSuchMethodException e) {
            return List.of();
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new ValidationException("Cannot read constraint container " + container.annotationType().getName(), e);
        }
    }

    private static Map<CharSequence, Object> annotationMembers(Annotation annotation, boolean defaultsOnly) {
        Map<CharSequence, Object> values = new LinkedHashMap<>();
        for (Method method : annotation.annotationType().getDeclaredMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object value = defaultsOnly ? method.getDefaultValue() : method.invoke(annotation);
                if (value != null) {
                    values.put(method.getName(), value);
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ValidationException("Cannot read annotation member " + annotation.annotationType().getName() + "." + method.getName(), e);
            }
        }
        return values;
    }

    private static Set<ContainerElementTypeDescriptor> containerElementDescriptors(List<ReflectionContainerElement> containerElements) {
        Map<ContainerElementKey, List<ReflectionContainerElement>> grouped = new LinkedHashMap<>();
        for (ReflectionContainerElement containerElement : containerElements) {
            if (containerElement.constraints.isEmpty()
                && !containerElement.cascaded
                && containerElement.nestedContainerElements.isEmpty()) {
                continue;
            }
            grouped.computeIfAbsent(
                new ContainerElementKey(containerElement.containerType, containerElement.typeArgumentIndex),
                ignored -> new ArrayList<>()
            ).add(containerElement);
        }
        return grouped.entrySet()
            .stream()
            .map(entry -> {
                ReflectionContainerElement first = entry.getValue().get(0);
                return new ReflectionContainerElementDescriptor(
                    entry.getKey().containerType(),
                    entry.getKey().typeArgumentIndex(),
                    first.type,
                    entry.getValue()
                );
            })
            .collect(Collectors.toUnmodifiableSet());
    }

    private static List<ReflectionExecutableParameter> parametersFor(Method method) {
        List<Method> hierarchy = ReflectionMethodDeclarations.hierarchy(method);
        Parameter[] localParameters = method.getParameters();
        List<ReflectionExecutableParameter> parameters = new ArrayList<>(localParameters.length);
        for (int i = 0; i < localParameters.length; i++) {
            List<ReflectionConstraintDescriptor<?>> constraints = new ArrayList<>();
            List<ReflectionContainerElement> containerElements = new ArrayList<>();
            Set<ReflectionGroupConversionDescriptor> groupConversions = new LinkedHashSet<>();
            boolean cascaded = false;
            for (Method hierarchyMethod : hierarchy) {
                Parameter parameter = hierarchyMethod.getParameters()[i];
                constraints.addAll(constraintsFor(parameter));
                addAllContainerElements(containerElements, containerElementsFor(parameter.getAnnotatedType()));
                groupConversions.addAll(groupConversionsFor(parameter, parameter.getAnnotatedType()));
                cascaded |= isCascaded(parameter);
            }
            parameters.add(new ReflectionExecutableParameter(
                i,
                localParameters[i].getName(),
                method.getParameterTypes()[i],
                List.copyOf(constraints),
                cascaded,
                Set.copyOf(groupConversions),
                List.copyOf(containerElements)
            ));
        }
        return List.copyOf(parameters);
    }

    private static List<ReflectionExecutableParameter> parametersFor(Constructor<?> constructor) {
        Parameter[] constructorParameters = constructor.getParameters();
        List<ReflectionExecutableParameter> parameters = new ArrayList<>(constructorParameters.length);
        for (int i = 0; i < constructorParameters.length; i++) {
            Parameter parameter = constructorParameters[i];
            parameters.add(new ReflectionExecutableParameter(
                i,
                parameter.getName(),
                constructor.getParameterTypes()[i],
                constraintsFor(parameter),
                isCascaded(parameter),
                groupConversionsFor(parameter, parameter.getAnnotatedType()),
                containerElementsFor(parameter.getAnnotatedType())
            ));
        }
        return List.copyOf(parameters);
    }

    private static ReflectionExecutableReturnValue returnValueFor(Method method) {
        List<ReflectionConstraintDescriptor<?>> constraints = new ArrayList<>();
        List<ReflectionContainerElement> containerElements = new ArrayList<>();
        Set<ReflectionGroupConversionDescriptor> groupConversions = new LinkedHashSet<>();
        boolean cascaded = false;
        for (Method hierarchyMethod : ReflectionMethodDeclarations.hierarchy(method)) {
            constraintsFor(hierarchyMethod).stream()
                .filter(constraint -> isTargetedConstraint(constraint, ConstraintTarget.RETURN_VALUE))
                .forEach(constraints::add);
            addAllContainerElements(containerElements, containerElementsFor(hierarchyMethod.getAnnotatedReturnType()));
            groupConversions.addAll(groupConversionsFor(hierarchyMethod, hierarchyMethod.getAnnotatedReturnType()));
            cascaded |= isCascaded(hierarchyMethod);
        }
        return new ReflectionExecutableReturnValue(
            method.getReturnType(),
            List.copyOf(constraints),
            cascaded,
            Set.copyOf(groupConversions),
            List.copyOf(containerElements)
        );
    }

    private static ReflectionExecutableReturnValue returnValueFor(Constructor<?> constructor) {
        List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(constructor).stream()
            .filter(constraint -> isTargetedConstraint(constraint, ConstraintTarget.RETURN_VALUE))
            .toList();
        return new ReflectionExecutableReturnValue(
            constructor.getDeclaringClass(),
            constraints,
            constructor.isAnnotationPresent(Valid.class),
            groupConversionsFor(constructor, constructor.getAnnotatedReturnType()),
            List.of()
        );
    }

    private static List<ReflectionExecutableConstraint> crossParameterConstraintsFor(Method method, Class<?> beanType) {
        List<ReflectionExecutableConstraint> constraints = new ArrayList<>();
        boolean local = method.getDeclaringClass() == beanType;
        for (Method hierarchyMethod : ReflectionMethodDeclarations.hierarchy(method)) {
            for (ReflectionConstraintDescriptor<?> constraint : constraintsFor(hierarchyMethod)) {
                if (isTargetedConstraint(constraint, ConstraintTarget.PARAMETERS)) {
                    constraints.add(new ReflectionExecutableConstraint(constraint, local));
                }
            }
            local = false;
        }
        return List.copyOf(constraints);
    }

    private static List<ReflectionExecutableConstraint> crossParameterConstraintsFor(Constructor<?> constructor) {
        return constraintsFor(constructor).stream()
            .filter(constraint -> isTargetedConstraint(constraint, ConstraintTarget.PARAMETERS))
            .map(constraint -> new ReflectionExecutableConstraint(constraint, true))
            .toList();
    }

    private static boolean isTargetedConstraint(ReflectionConstraintDescriptor<?> descriptor, ConstraintTarget target) {
        return appliesTo(descriptor, target) && supportsValidationTarget(descriptor, target);
    }

    private static boolean supportsValidationTarget(ReflectionConstraintDescriptor<?> descriptor, ConstraintTarget target) {
        Set<ValidationTarget> targets = new LinkedHashSet<>();
        for (Class<? extends jakarta.validation.ConstraintValidator<?, ?>> validatorClass : descriptor.getConstraintValidatorClasses()) {
            SupportedValidationTarget supportedValidationTarget = validatorClass.getAnnotation(SupportedValidationTarget.class);
            if (supportedValidationTarget == null) {
                targets.add(ValidationTarget.ANNOTATED_ELEMENT);
            } else {
                targets.addAll(Arrays.asList(supportedValidationTarget.value()));
            }
        }
        if (targets.isEmpty()) {
            targets.add(ValidationTarget.ANNOTATED_ELEMENT);
        }
        return target == ConstraintTarget.PARAMETERS
            ? targets.contains(ValidationTarget.PARAMETERS)
            : targets.contains(ValidationTarget.ANNOTATED_ELEMENT);
    }

    private record ReflectionProperty(
        String name,
        Class<?> type,
        AccessibleObject source,
        List<ReflectionConstraintDescriptor<?>> constraints,
        Set<ReflectionGroupConversionDescriptor> groupConversions,
        List<ReflectionContainerElement> containerElements,
        boolean cascaded
    ) {
        ReflectionProperty(String name,
                           Class<?> type,
                           AccessibleObject source,
                           List<ReflectionConstraintDescriptor<?>> constraints,
                           Set<ReflectionGroupConversionDescriptor> groupConversions,
                           List<ReflectionContainerElement> containerElements) {
            this(name, type, source, constraints, groupConversions, containerElements, false);
        }

        Object read(Object bean) {
            try {
                source.trySetAccessible();
                if (source instanceof Field field) {
                    return field.get(bean);
                }
                return ((Method) source).invoke(bean);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ValidationException("Cannot read property " + name + " using reflection", e);
            }
        }

        boolean isCascaded() {
            if (cascaded) {
                return true;
            }
            if (source.isAnnotationPresent(Valid.class)) {
                return true;
            }
            AnnotatedType annotatedType = source instanceof Field field
                ? field.getAnnotatedType()
                : ((Method) source).getAnnotatedReturnType();
            return annotatedType.isAnnotationPresent(Valid.class);
        }

        Class<?> declaringClass() {
            return source instanceof Field field ? field.getDeclaringClass() : ((Method) source).getDeclaringClass();
        }

        ElementType elementType() {
            return source instanceof Field ? ElementType.FIELD : ElementType.METHOD;
        }
    }

    private record ConstraintKey(
        Class<? extends Annotation> annotationType,
        Map<String, Object> attributes
    ) {

        static ConstraintKey of(ReflectionConstraintDescriptor<?> constraint) {
            return new ConstraintKey(constraint.getAnnotation().annotationType(), normalizeAttributes(constraint.getAttributes()));
        }

        static ConstraintKey of(ConstraintDescriptor<?> constraint) {
            return new ConstraintKey(constraint.getAnnotation().annotationType(), normalizeAttributes(constraint.getAttributes()));
        }

        static ConstraintKey of(Class<? extends Annotation> annotationType,
                                AnnotationValue<? extends Annotation> annotationValue) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            annotationValue.getValues().forEach((key, value) -> {
                String attributeName = key.toString();
                if (!attributeName.startsWith("$")) {
                    attributes.put(attributeName, normalize(value));
                }
            });
            Map<CharSequence, Object> defaultValues = annotationValue.getDefaultValues();
            if (defaultValues != null) {
                defaultValues.forEach((key, value) -> {
                    String attributeName = key.toString();
                    if (!attributeName.startsWith("$")) {
                        attributes.putIfAbsent(attributeName, normalize(value));
                    }
                });
            }
            return new ConstraintKey(annotationType, attributes);
        }

        private static Map<String, Object> normalizeAttributes(Map<String, Object> attributes) {
            return attributes.entrySet()
                .stream()
                .filter(entry -> !entry.getKey().startsWith("$"))
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> normalize(entry.getValue()),
                    (left, right) -> left,
                    LinkedHashMap::new
                ));
        }

        private static Object normalize(Object value) {
            if (value instanceof Class<?> classValue) {
                return classValue.getName();
            }
            if (value instanceof Class<?>[] classValues) {
                return Arrays.stream(classValues)
                    .map(Class::getName)
                    .sorted()
                    .toList();
            }
            if (value instanceof Object[] array) {
                return Arrays.stream(array)
                    .map(ConstraintKey::normalize)
                    .toList();
            }
            return value;
        }
    }

    private record ReflectionContainerElement(
        Class<?> containerType,
        int typeArgumentIndex,
        Class<?> type,
        List<ReflectionConstraintDescriptor<?>> constraints,
        boolean cascaded,
        Set<ReflectionGroupConversionDescriptor> groupConversions,
        List<ReflectionContainerElement> nestedContainerElements,
        boolean providerDeclared
    ) {
    }

    private static void addAllContainerElements(List<ReflectionContainerElement> containerElements,
                                                List<ReflectionContainerElement> candidates) {
        for (ReflectionContainerElement candidate : candidates) {
            addContainerElement(containerElements, candidate);
        }
    }

    private static void addContainerElement(List<ReflectionContainerElement> containerElements,
                                            ReflectionContainerElement candidate) {
        for (ReflectionContainerElement containerElement : containerElements) {
            if (sameContainerElement(containerElement, candidate)) {
                return;
            }
        }
        containerElements.add(candidate);
    }

    private static boolean sameContainerElement(ReflectionContainerElement left,
                                                ReflectionContainerElement right) {
        return left.containerType == right.containerType
            && left.typeArgumentIndex == right.typeArgumentIndex
            && left.type == right.type
            && left.cascaded == right.cascaded
            && left.groupConversions.equals(right.groupConversions)
            && constraintKeys(left.constraints).equals(constraintKeys(right.constraints))
            && sameContainerElements(left.nestedContainerElements, right.nestedContainerElements);
    }

    private static boolean sameContainerElements(List<ReflectionContainerElement> left,
                                                 List<ReflectionContainerElement> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (ReflectionContainerElement leftElement : left) {
            boolean matched = false;
            for (ReflectionContainerElement rightElement : right) {
                if (sameContainerElement(leftElement, rightElement)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static Set<ConstraintKey> constraintKeys(List<ReflectionConstraintDescriptor<?>> constraints) {
        return constraints.stream()
            .map(ConstraintKey::of)
            .collect(Collectors.toUnmodifiableSet());
    }

    private record ReflectionGroupConversionDescriptor(
        Class<?> from,
        Class<?> to
    ) implements GroupConversionDescriptor {

        @Override
        public Class<?> getFrom() {
            return from;
        }

        @Override
        public Class<?> getTo() {
            return to;
        }
    }

    private record ContainerElementKey(
        Class<?> containerType,
        int typeArgumentIndex
    ) {
    }

    private record ReflectionExecutableConstraint(
        ReflectionConstraintDescriptor<?> descriptor,
        boolean local
    ) {
    }

    private record ReflectionExecutableParameter(
        int index,
        String name,
        Class<?> type,
        List<ReflectionConstraintDescriptor<?>> constraints,
        boolean cascaded,
        Set<ReflectionGroupConversionDescriptor> groupConversions,
        List<ReflectionContainerElement> containerElements
    ) {
    }

    private record ReflectionExecutableReturnValue(
        Class<?> type,
        List<ReflectionConstraintDescriptor<?>> constraints,
        boolean cascaded,
        Set<ReflectionGroupConversionDescriptor> groupConversions,
        List<ReflectionContainerElement> containerElements
    ) {
    }

    private abstract static class AbstractReflectionExecutableDescriptor implements ElementDescriptor.ConstraintFinder {

        final Executable executable;
        final List<ReflectionExecutableParameter> parameters;
        final ReflectionCrossParameterDescriptor crossParameterDescriptor;
        final ReflectionReturnValueDescriptor returnValueDescriptor;

        AbstractReflectionExecutableDescriptor(Executable executable,
                                               List<ReflectionExecutableParameter> parameters,
                                               ReflectionCrossParameterDescriptor crossParameterDescriptor,
                                               ReflectionReturnValueDescriptor returnValueDescriptor) {
            this.executable = executable;
            this.parameters = parameters;
            this.crossParameterDescriptor = crossParameterDescriptor;
            this.returnValueDescriptor = returnValueDescriptor;
        }

        public String getName() {
            return executable instanceof Constructor<?> constructor ? constructor.getDeclaringClass().getSimpleName() : executable.getName();
        }

        public List<ParameterDescriptor> getParameterDescriptors() {
            return parameters.stream()
                .map(ReflectionParameterDescriptor::new)
                .collect(Collectors.toUnmodifiableList());
        }

        public CrossParameterDescriptor getCrossParameterDescriptor() {
            return crossParameterDescriptor;
        }

        public ReturnValueDescriptor getReturnValueDescriptor() {
            return returnValueDescriptor;
        }

        public boolean hasConstrainedParameters() {
            return crossParameterDescriptor.hasConstraints() || parameters.stream()
                .anyMatch(parameter -> !parameter.constraints.isEmpty()
                    || parameter.cascaded
                    || !parameter.containerElements.isEmpty());
        }

        public boolean hasConstrainedReturnValue() {
            return returnValueDescriptor.hasConstraints()
                || returnValueDescriptor.isCascaded()
                || !returnValueDescriptor.getConstrainedContainerElementTypes().isEmpty();
        }

        @Override
        public boolean hasConstraints() {
            return false;
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return Set.of();
        }

        public ElementDescriptor.ConstraintFinder findConstraints() {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }
    }

    private static final class ReflectionMethodDescriptor extends AbstractReflectionExecutableDescriptor implements MethodDescriptor {

        private final Method method;

        ReflectionMethodDescriptor(Method method) {
            this(method, method.getDeclaringClass());
        }

        ReflectionMethodDescriptor(Method method, Class<?> beanType) {
            super(
                method,
                parametersFor(method),
                new ReflectionCrossParameterDescriptor(crossParameterConstraintsFor(method, beanType)),
                new ReflectionReturnValueDescriptor(returnValueFor(method))
            );
            this.method = method;
        }

        @Override
        public Class<?> getElementClass() {
            return method.getReturnType();
        }

        boolean isConstrained() {
            return hasConstrainedParameters() || hasConstrainedReturnValue();
        }
    }

    private static final class ReflectionConstructorDescriptor extends AbstractReflectionExecutableDescriptor implements ConstructorDescriptor {

        private final Constructor<?> constructor;

        ReflectionConstructorDescriptor(Constructor<?> constructor) {
            super(
                constructor,
                parametersFor(constructor),
                new ReflectionCrossParameterDescriptor(crossParameterConstraintsFor(constructor)),
                new ReflectionReturnValueDescriptor(returnValueFor(constructor))
            );
            this.constructor = constructor;
        }

        @Override
        public Class<?> getElementClass() {
            return constructor.getDeclaringClass();
        }

        boolean isConstrained() {
            return hasConstrainedParameters() || hasConstrainedReturnValue();
        }
    }

    private record ReflectionCrossParameterDescriptor(
        List<ReflectionExecutableConstraint> constraints,
        Scope scope,
        Set<Class<?>> groups
    ) implements CrossParameterDescriptor, ElementDescriptor.ConstraintFinder {

        private ReflectionCrossParameterDescriptor(List<ReflectionExecutableConstraint> constraints) {
            this(constraints, Scope.HIERARCHY, Set.of());
        }

        @Override
        public Class<?> getElementClass() {
            return Object[].class;
        }

        @Override
        public boolean hasConstraints() {
            return !getConstraintDescriptors().isEmpty();
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return constraints.stream()
                .filter(this::matchesScope)
                .map(ReflectionExecutableConstraint::descriptor)
                .filter(this::matchesGroups)
                .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public ElementDescriptor.ConstraintFinder findConstraints() {
            return new ReflectionCrossParameterDescriptor(constraints);
        }

        @Override
        public ElementDescriptor.ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return new ReflectionCrossParameterDescriptor(constraints, scope, Set.of(groups));
        }

        @Override
        public ElementDescriptor.ConstraintFinder lookingAt(Scope scope) {
            return new ReflectionCrossParameterDescriptor(constraints, scope, groups);
        }

        @Override
        public ElementDescriptor.ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }

        private boolean matchesScope(ReflectionExecutableConstraint constraint) {
            return scope == Scope.HIERARCHY || constraint.local;
        }

        private boolean matchesGroups(ReflectionConstraintDescriptor<?> descriptor) {
            return groups.isEmpty() || groups.stream().anyMatch(descriptor.getGroups()::contains);
        }
    }

    private record ReflectionParameterDescriptor(
        ReflectionExecutableParameter parameter,
        Set<Class<?>> groups
    ) implements ParameterDescriptor, ElementDescriptor.ConstraintFinder {

        private ReflectionParameterDescriptor(ReflectionExecutableParameter parameter) {
            this(parameter, Set.of());
        }

        @Override
        public int getIndex() {
            return parameter.index;
        }

        @Override
        public String getName() {
            return parameter.name;
        }

        @Override
        public boolean isCascaded() {
            return parameter.cascaded;
        }

        @Override
        public Set<GroupConversionDescriptor> getGroupConversions() {
            return Set.copyOf(parameter.groupConversions);
        }

        @Override
        public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
            return containerElementDescriptors(parameter.containerElements);
        }

        @Override
        public boolean hasConstraints() {
            return !getConstraintDescriptors().isEmpty();
        }

        @Override
        public Class<?> getElementClass() {
            return parameter.type;
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return parameter.constraints.stream()
                .filter(this::matchesGroups)
                .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public ElementDescriptor.ConstraintFinder findConstraints() {
            return new ReflectionParameterDescriptor(parameter);
        }

        @Override
        public ElementDescriptor.ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return new ReflectionParameterDescriptor(parameter, Set.of(groups));
        }

        @Override
        public ElementDescriptor.ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }

        private boolean matchesGroups(ReflectionConstraintDescriptor<?> descriptor) {
            return groups.isEmpty() || groups.stream().anyMatch(descriptor.getGroups()::contains);
        }
    }

    private record ReflectionReturnValueDescriptor(
        ReflectionExecutableReturnValue returnValue,
        Set<Class<?>> groups
    ) implements ReturnValueDescriptor, ElementDescriptor.ConstraintFinder {

        private ReflectionReturnValueDescriptor(ReflectionExecutableReturnValue returnValue) {
            this(returnValue, Set.of());
        }

        @Override
        public boolean isCascaded() {
            return returnValue.cascaded;
        }

        @Override
        public Set<GroupConversionDescriptor> getGroupConversions() {
            return Set.copyOf(returnValue.groupConversions);
        }

        @Override
        public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
            return containerElementDescriptors(returnValue.containerElements);
        }

        @Override
        public boolean hasConstraints() {
            return !getConstraintDescriptors().isEmpty();
        }

        @Override
        public Class<?> getElementClass() {
            return returnValue.type;
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return returnValue.constraints.stream()
                .filter(this::matchesGroups)
                .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public ElementDescriptor.ConstraintFinder findConstraints() {
            return new ReflectionReturnValueDescriptor(returnValue);
        }

        @Override
        public ElementDescriptor.ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return new ReflectionReturnValueDescriptor(returnValue, Set.of(groups));
        }

        @Override
        public ElementDescriptor.ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }

        private boolean matchesGroups(ReflectionConstraintDescriptor<?> descriptor) {
            return groups.isEmpty() || groups.stream().anyMatch(descriptor.getGroups()::contains);
        }
    }

    private record ReflectionSupplementedBeanDescriptor(
        BeanDescriptor generated,
        ReflectionBeanMetadata reflected,
        Class<?> beanType,
        List<ValidationMetadataProvider> metadataProviders
    ) implements BeanDescriptor {

        @Override
        public boolean isBeanConstrained() {
            return hasConstraints() || getConstrainedProperties().stream().anyMatch(ReflectionSupplementedBeanDescriptor::isConstrained);
        }

        @Override
        public @Nullable PropertyDescriptor getConstraintsForProperty(@Nullable String propertyName) {
            if (propertyName == null) {
                throw new IllegalArgumentException("Property name cannot be null");
            }
            PropertyDescriptor generatedProperty = generated.getConstraintsForProperty(propertyName);
            PropertyDescriptor reflectedProperty = isPropertyAnnotationMetadataIgnored(propertyName)
                ? null
                : reflected.getConstraintsForProperty(propertyName);
            if (generatedProperty != null && !isConstrained(generatedProperty)) {
                return null;
            }
            if (generatedProperty == null) {
                return reflectedProperty;
            }
            if (reflectedProperty == null) {
                return generatedProperty;
            }
            if (hasDuplicateConstraintTypes(generatedProperty) && !hasDuplicateConstraintTypes(reflectedProperty)) {
                return reflectedProperty;
            }
            if (hasExpandedComposedConstraints(generatedProperty, reflectedProperty)) {
                return reflectedProperty;
            }
            boolean generatedHasConfiguredValidators = hasConfiguredConstraintValidatorClasses(generatedProperty);
            boolean reflectedHasConfiguredValidators = hasConfiguredConstraintValidatorClasses(reflectedProperty);
            if (generatedHasConfiguredValidators && !reflectedHasConfiguredValidators) {
                return generatedProperty;
            }
            if (reflectedHasConfiguredValidators && !generatedHasConfiguredValidators) {
                return reflectedProperty;
            }
            return constraintWeight(reflectedProperty) >= constraintWeight(generatedProperty)
                ? reflectedProperty
                : generatedProperty;
        }

        @Override
        public Set<PropertyDescriptor> getConstrainedProperties() {
            Map<String, PropertyDescriptor> properties = generated.getConstrainedProperties()
                .stream()
                .collect(Collectors.toMap(PropertyDescriptor::getPropertyName, Function.identity(), (left, right) -> left, LinkedHashMap::new));
            for (PropertyDescriptor reflectedProperty : reflected.getConstrainedProperties()) {
                if (isPropertyAnnotationMetadataIgnored(reflectedProperty.getPropertyName())) {
                    continue;
                }
                properties.merge(
                    reflectedProperty.getPropertyName(),
                    reflectedProperty,
                    (generatedProperty, replacement) -> {
                        boolean generatedHasConfiguredValidators = hasConfiguredConstraintValidatorClasses(generatedProperty);
                        boolean replacementHasConfiguredValidators = hasConfiguredConstraintValidatorClasses(replacement);
                        return hasDuplicateConstraintTypes(generatedProperty) && !hasDuplicateConstraintTypes(replacement)
                            || hasExpandedComposedConstraints(generatedProperty, replacement)
                            || replacementHasConfiguredValidators && !generatedHasConfiguredValidators
                            || replacementHasConfiguredValidators == generatedHasConfiguredValidators
                            && constraintWeight(replacement) >= constraintWeight(generatedProperty)
                            ? replacement
                            : generatedProperty;
                    }
                );
            }
            return new LinkedHashSet<>(properties.values());
        }

        @Override
        public @Nullable MethodDescriptor getConstraintsForMethod(@Nullable String methodName, Class<?>... parameterTypes) {
            MethodDescriptor generatedMethod = generated.getConstraintsForMethod(methodName, parameterTypes);
            if (generatedMethod != null) {
                return generatedMethod;
            }
            MethodDescriptor reflectedMethod = reflected.getConstraintsForMethod(methodName, parameterTypes);
            return reflectedMethod == null ? null : reflectedMethod;
        }

        @Override
        public Set<MethodDescriptor> getConstrainedMethods(MethodType methodType, MethodType... methodTypes) {
            Map<ExecutableDescriptorKey, MethodDescriptor> methods = generated.getConstrainedMethods(methodType, methodTypes)
                .stream()
                .collect(Collectors.toMap(
                    method -> ExecutableDescriptorKey.of(method.getName(), method.getParameterDescriptors()),
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new
                ));
            Set<MethodDescriptor> reflectedMethods = reflected.getConstrainedMethods(methodType, methodTypes);
            for (MethodDescriptor reflectedMethod : reflectedMethods) {
                methods.putIfAbsent(
                    ExecutableDescriptorKey.of(reflectedMethod.getName(), reflectedMethod.getParameterDescriptors()),
                    reflectedMethod
                );
            }
            return new LinkedHashSet<>(methods.values());
        }

        @Override
        public @Nullable ConstructorDescriptor getConstraintsForConstructor(Class<?>... parameterTypes) {
            ConstructorDescriptor generatedConstructor = generated.getConstraintsForConstructor(parameterTypes);
            if (generatedConstructor != null) {
                return generatedConstructor;
            }
            ConstructorDescriptor reflectedConstructor = reflected.getConstraintsForConstructor(parameterTypes);
            return reflectedConstructor == null ? null : reflectedConstructor;
        }

        @Override
        public Set<ConstructorDescriptor> getConstrainedConstructors() {
            Map<ExecutableDescriptorKey, ConstructorDescriptor> constructors = generated.getConstrainedConstructors()
                .stream()
                .collect(Collectors.toMap(
                    constructor -> ExecutableDescriptorKey.of(null, constructor.getParameterDescriptors()),
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new
                ));
            Set<ConstructorDescriptor> reflectedConstructors = reflected.getConstrainedConstructors();
            for (ConstructorDescriptor reflectedConstructor : reflectedConstructors) {
                constructors.putIfAbsent(
                    ExecutableDescriptorKey.of(null, reflectedConstructor.getParameterDescriptors()),
                    reflectedConstructor
                );
            }
            return new LinkedHashSet<>(constructors.values());
        }

        @Override
        public boolean hasConstraints() {
            return generated.hasConstraints() || (!isBeanAnnotationMetadataIgnored() && reflected.hasConstraints());
        }

        @Override
        public Class<?> getElementClass() {
            return generated.getElementClass();
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return generated.getConstraintDescriptors();
        }

        @Override
        public ConstraintFinder findConstraints() {
            return generated.findConstraints();
        }

        private record ExecutableDescriptorKey(@Nullable String name, List<Class<?>> parameterTypes) {

            private static ExecutableDescriptorKey of(@Nullable String name, List<ParameterDescriptor> parameterDescriptors) {
                return new ExecutableDescriptorKey(name, parameterDescriptors.stream()
                    .map(ParameterDescriptor::getElementClass)
                    .toList());
            }
        }

        private boolean isBeanAnnotationMetadataIgnored() {
            return metadataProviders.stream()
                .anyMatch(provider -> provider.isBeanAnnotationMetadataIgnored(beanType));
        }

        private boolean isPropertyAnnotationMetadataIgnored(String propertyName) {
            return metadataProviders.stream()
                .anyMatch(provider -> provider.isPropertyAnnotationMetadataIgnored(beanType, propertyName));
        }

        private static int constraintWeight(PropertyDescriptor propertyDescriptor) {
            return propertyDescriptor.getConstraintDescriptors()
                .stream()
                .mapToInt(ReflectionSupplementedBeanDescriptor::constraintWeight)
                .sum();
        }

        private static boolean isConstrained(PropertyDescriptor propertyDescriptor) {
            return propertyDescriptor.hasConstraints()
                || propertyDescriptor.isCascaded()
                || !propertyDescriptor.getGroupConversions().isEmpty()
                || !propertyDescriptor.getConstrainedContainerElementTypes().isEmpty();
        }

        private static boolean hasDuplicateConstraintTypes(PropertyDescriptor propertyDescriptor) {
            Set<Class<? extends Annotation>> constraintTypes = new LinkedHashSet<>();
            for (ConstraintDescriptor<?> descriptor : propertyDescriptor.getConstraintDescriptors()) {
                if (!constraintTypes.add(descriptor.getAnnotation().annotationType())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasExpandedComposedConstraints(PropertyDescriptor generatedProperty,
                                                              PropertyDescriptor reflectedProperty) {
            return generatedProperty.getConstraintDescriptors().size() > reflectedProperty.getConstraintDescriptors().size()
                && reflectedProperty.getConstraintDescriptors()
                .stream()
                .anyMatch(descriptor -> !descriptor.getComposingConstraints().isEmpty());
        }

        private static boolean hasConfiguredConstraintValidatorClasses(PropertyDescriptor propertyDescriptor) {
            for (ConstraintDescriptor<?> descriptor : propertyDescriptor.getConstraintDescriptors()) {
                Constraint constraint = descriptor.getAnnotation().annotationType().getAnnotation(Constraint.class);
                List<Class<? extends jakarta.validation.ConstraintValidator<?, ?>>> defaultValidators = constraint == null
                    ? List.of()
                    : (List) List.of(constraint.validatedBy());
                if (!descriptor.getConstraintValidatorClasses().equals(defaultValidators)) {
                    return true;
                }
            }
            return false;
        }

        private static int constraintWeight(ConstraintDescriptor<?> descriptor) {
            return 1 + descriptor.getComposingConstraints()
                .stream()
                .mapToInt(ReflectionSupplementedBeanDescriptor::constraintWeight)
                .sum();
        }
    }

    /**
     * Reflection-backed bean descriptor used by the optional metadata provider.
     *
     * <p>This descriptor is package-private so reflection metadata can be shared
     * between {@link ReflectionValidator} and
     * {@link ReflectionValidationMetadataProvider} without widening the public
     * API. Maintainers should keep generated metadata as the primary source and
     * use this descriptor only as the Jakarta compatibility fallback.</p>
     */
    static final class ReflectionBeanMetadata implements BeanDescriptor, ElementDescriptor.ConstraintFinder {

        private final Class<?> beanType;
        private final List<ReflectionConstraintDescriptor<?>> constraints;
        private final Map<String, List<ReflectionProperty>> properties;

        private ReflectionBeanMetadata(Class<?> beanType,
                                       List<ReflectionConstraintDescriptor<?>> constraints,
                                       Map<String, List<ReflectionProperty>> properties) {
            this.beanType = beanType;
            this.constraints = constraints;
            this.properties = properties;
        }

        /**
         * Builds reflection metadata without external overlays.
         *
         * @param beanType The bean type to inspect
         * @return Reflection metadata for the bean
         */
        static ReflectionBeanMetadata of(Class<?> beanType) {
            return of(beanType, List.of());
        }

        /**
         * Builds reflection metadata with optional provider overlays such as XML
         * mappings.
         *
         * @param beanType The bean type to inspect
         * @param metadataProviders Metadata providers that can replace or
         * supplement annotation metadata
         * @return Reflection metadata for the bean
         */
        static ReflectionBeanMetadata of(Class<?> beanType, List<ValidationMetadataProvider> metadataProviders) {
            Map<String, List<ReflectionProperty>> properties = new LinkedHashMap<>();
            for (Class<?> current = beanType; current != null && current != Object.class; current = current.getSuperclass()) {
                Class<?> implicitGroup = current.isInterface() ? null : current;
                collectClassFieldProperties(current, implicitGroup, metadataProviders, properties);
                collectClassMethodProperties(current, implicitGroup, metadataProviders, properties);
            }
            collectInterfaceProperties(beanType, properties, new LinkedHashSet<>());
            List<ReflectionConstraintDescriptor<?>> constraints = new ArrayList<>();
            collectTypeConstraints(beanType, constraints, new LinkedHashSet<>());
            return new ReflectionBeanMetadata(beanType, constraints, properties);
        }

        private static void collectClassFieldProperties(Class<?> type,
                                                        @Nullable Class<?> implicitGroup,
                                                        List<ValidationMetadataProvider> metadataProviders,
                                                        Map<String, List<ReflectionProperty>> properties) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(field, implicitGroup, metadataProviders);
                List<ReflectionContainerElement> containerElements = containerElementsFor(field.getAnnotatedType());
                if (!constraints.isEmpty() || !containerElements.isEmpty() || isCascaded(field)) {
                    addProperty(properties, new ReflectionProperty(
                        field.getName(),
                        field.getType(),
                        field,
                        constraints,
                        groupConversionsFor(field, field.getAnnotatedType()),
                        containerElements
                    ));
                }
            }
        }

        private static void collectClassMethodProperties(Class<?> type,
                                                         @Nullable Class<?> implicitGroup,
                                                         List<ValidationMetadataProvider> metadataProviders,
                                                         Map<String, List<ReflectionProperty>> properties) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || method.getReturnType() == Void.TYPE || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                String propertyName = propertyName(method);
                if (propertyName == null) {
                    continue;
                }
                List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(method, implicitGroup, metadataProviders);
                List<ReflectionContainerElement> containerElements = containerElementsFor(method.getAnnotatedReturnType());
                if (!constraints.isEmpty() || !containerElements.isEmpty() || isCascaded(method)) {
                    addProperty(properties, new ReflectionProperty(
                        propertyName,
                        method.getReturnType(),
                        method,
                        constraints,
                        groupConversionsFor(method, method.getAnnotatedReturnType()),
                        containerElements
                    ));
                }
            }
        }

        private static void addProperty(Map<String, List<ReflectionProperty>> properties, ReflectionProperty property) {
            properties.computeIfAbsent(property.name, ignored -> new ArrayList<>()).add(property);
        }

        private static void collectInterfaceProperties(@Nullable Class<?> type,
                                                       Map<String, List<ReflectionProperty>> properties,
                                                       Set<Class<?>> visited) {
            if (type == null || type == Object.class) {
                return;
            }
            for (Class<?> interfaceType : type.getInterfaces()) {
                collectInterfaceProperties(interfaceType, interfaceType, properties, visited);
            }
            collectInterfaceProperties(type.getSuperclass(), properties, visited);
        }

        private static void collectInterfaceProperties(Class<?> interfaceType,
                                                       Class<?> implicitGroup,
                                                       Map<String, List<ReflectionProperty>> properties,
                                                       Set<Class<?>> visited) {
            if (!visited.add(interfaceType)) {
                return;
            }
            for (Method method : interfaceType.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || method.getReturnType() == Void.TYPE || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                String propertyName = propertyName(method);
                if (propertyName == null) {
                    continue;
                }
                List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(method, implicitGroup);
                List<ReflectionContainerElement> containerElements = containerElementsFor(method.getAnnotatedReturnType());
                if (!constraints.isEmpty() || !containerElements.isEmpty() || isCascaded(method)) {
                    addProperty(properties, new ReflectionProperty(
                        propertyName,
                        method.getReturnType(),
                        method,
                        constraints,
                        groupConversionsFor(method, method.getAnnotatedReturnType()),
                        containerElements
                    ));
                }
            }
            for (Class<?> parent : interfaceType.getInterfaces()) {
                collectInterfaceProperties(parent, implicitGroup, properties, visited);
            }
        }

        private static void collectTypeConstraints(@Nullable Class<?> type,
                                                   List<ReflectionConstraintDescriptor<?>> constraints,
                                                   Set<Class<?>> visited) {
            if (type == null || type == Object.class || !visited.add(type)) {
                return;
            }
            constraints.addAll(constraintsFor(type, type.isInterface() ? type : null));
            for (Class<?> interfaceType : type.getInterfaces()) {
                collectTypeConstraints(interfaceType, constraints, visited);
            }
            collectTypeConstraints(type.getSuperclass(), constraints, visited);
        }

        @Nullable
        private static String propertyName(Method method) {
            String name = method.getName();
            if (name.startsWith("get") && name.length() > 3) {
                return Character.toLowerCase(name.charAt(3)) + name.substring(4);
            }
            if (name.startsWith("is") && name.length() > 2 && method.getReturnType() == boolean.class) {
                return Character.toLowerCase(name.charAt(2)) + name.substring(3);
            }
            return null;
        }

        @Override
        public boolean isBeanConstrained() {
            return hasConstraints() || properties.values()
                .stream()
                .flatMap(List::stream)
                .anyMatch(property -> !property.constraints.isEmpty() || !property.containerElements.isEmpty() || property.isCascaded());
        }

        @Override
        public @Nullable PropertyDescriptor getConstraintsForProperty(@Nullable String propertyName) {
            if (propertyName == null) {
                throw new IllegalArgumentException("Property name cannot be null");
            }
            List<ReflectionProperty> property = properties.get(propertyName);
            return property == null ? null : new ReflectionPropertyDescriptor(propertyName, property);
        }

        @Override
        public Set<PropertyDescriptor> getConstrainedProperties() {
            return properties.entrySet()
                .stream()
                .map(entry -> new ReflectionPropertyDescriptor(entry.getKey(), entry.getValue()))
                .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public @Nullable MethodDescriptor getConstraintsForMethod(@Nullable String methodName, Class<?>... parameterTypes) {
            if (methodName == null) {
                throw new IllegalArgumentException("Method name cannot be null");
            }
            Method method = findMethod(beanType, methodName, parameterTypes);
            if (method == null) {
                return null;
            }
            ReflectionMethodDescriptor descriptor = new ReflectionMethodDescriptor(method, beanType);
            return descriptor.isConstrained() ? descriptor : null;
        }

        @Override
        public Set<MethodDescriptor> getConstrainedMethods(MethodType methodType, MethodType... methodTypes) {
            Set<MethodType> requestedTypes = EnumSet.of(methodType, methodTypes);
            Set<MethodDescriptor> methods = new LinkedHashSet<>();
            for (Method method : beanType.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                boolean getter = method.getParameterCount() == 0 && propertyName(method) != null;
                if (requestedTypes.contains(getter ? MethodType.GETTER : MethodType.NON_GETTER)) {
                    ReflectionMethodDescriptor descriptor = new ReflectionMethodDescriptor(method, beanType);
                    if (descriptor.isConstrained()) {
                        methods.add(descriptor);
                    }
                }
            }
            return Set.copyOf(methods);
        }

        @Override
        public @Nullable ConstructorDescriptor getConstraintsForConstructor(Class<?>... parameterTypes) {
            Constructor<?> constructor = findConstructor(beanType, parameterTypes);
            if (constructor == null) {
                return null;
            }
            ReflectionConstructorDescriptor descriptor = new ReflectionConstructorDescriptor(constructor);
            return descriptor.isConstrained() ? descriptor : null;
        }

        @Override
        public Set<ConstructorDescriptor> getConstrainedConstructors() {
            Set<ConstructorDescriptor> constructors = new LinkedHashSet<>();
            for (Constructor<?> constructor : beanType.getDeclaredConstructors()) {
                ReflectionConstructorDescriptor descriptor = new ReflectionConstructorDescriptor(constructor);
                if (descriptor.isConstrained()) {
                    constructors.add(descriptor);
                }
            }
            return Set.copyOf(constructors);
        }

        @Override
        public boolean hasConstraints() {
            return !constraints.isEmpty();
        }

        @Override
        public Class<?> getElementClass() {
            return beanType;
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
            return Set.copyOf(constraints);
        }

        @Override
        public ConstraintFinder findConstraints() {
            return this;
        }

        private static @Nullable Method findMethod(Class<?> beanType, String methodName, Class<?>... parameterTypes) {
            try {
                return beanType.getMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException e) {
                for (Class<?> current = beanType; current != null && current != Object.class; current = current.getSuperclass()) {
                    try {
                        return current.getDeclaredMethod(methodName, parameterTypes);
                    } catch (NoSuchMethodException ignored) {
                        // Continue searching hierarchy.
                    }
                }
                return null;
            }
        }

        private static @Nullable Constructor<?> findConstructor(Class<?> beanType, Class<?>... parameterTypes) {
            try {
                return beanType.getDeclaredConstructor(parameterTypes);
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    }

    private record ReflectionPropertyDescriptor(
        String propertyName,
        List<ReflectionProperty> properties,
        Set<Class<?>> groups,
        Scope scope,
        Set<ElementType> declaredOn
    ) implements PropertyDescriptor, ElementDescriptor.ConstraintFinder {

        private ReflectionPropertyDescriptor(String propertyName, List<ReflectionProperty> properties) {
            this(propertyName, properties, Set.of(), Scope.HIERARCHY, Set.of());
        }

        @Override
        public String getPropertyName() {
            return propertyName;
        }

        @Override
        public boolean isCascaded() {
            return properties.stream().anyMatch(ReflectionProperty::isCascaded);
        }

        @Override
        public Set<GroupConversionDescriptor> getGroupConversions() {
            return properties.stream()
                .flatMap(property -> property.groupConversions.stream())
                .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
            return containerElementDescriptors(properties.stream()
                .filter(this::matchesScope)
                .filter(this::matchesDeclaredOn)
                .flatMap(property -> property.containerElements.stream())
                .toList());
        }

        @Override
        public boolean hasConstraints() {
            return properties.stream().anyMatch(property -> !property.constraints.isEmpty() || !property.containerElements.isEmpty());
        }

        @Override
        public Class<?> getElementClass() {
            return properties.get(0).type;
        }

        @Override
        public ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return new ReflectionPropertyDescriptor(propertyName, properties, Set.of(groups), scope, declaredOn);
        }

        @Override
        public ConstraintFinder lookingAt(Scope scope) {
            return new ReflectionPropertyDescriptor(propertyName, properties, groups, scope, declaredOn);
        }

        @Override
        public ConstraintFinder declaredOn(ElementType... types) {
            return new ReflectionPropertyDescriptor(propertyName, properties, groups, scope, Set.of(types));
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return properties.stream()
                .filter(this::matchesScope)
                .filter(this::matchesDeclaredOn)
                .flatMap(property -> property.constraints.stream())
                .filter(this::matchesGroups)
                .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public ConstraintFinder findConstraints() {
            return new ReflectionPropertyDescriptor(propertyName, properties);
        }

        private boolean matchesScope(ReflectionProperty property) {
            return scope == Scope.HIERARCHY || property.declaringClass() == localDeclaringClass();
        }

        private boolean matchesDeclaredOn(ReflectionProperty property) {
            return declaredOn.isEmpty() || declaredOn.contains(property.elementType());
        }

        private boolean matchesGroups(ReflectionConstraintDescriptor<?> descriptor) {
            if (groups.isEmpty()) {
                return true;
            }
            Set<Class<?>> effectiveGroups = effectiveGroups();
            for (Class<?> requestedGroup : effectiveGroups) {
                if (descriptor.matchesImplicitGroup(requestedGroup)) {
                    return true;
                }
                for (Class<?> descriptorGroup : descriptor.getGroups()) {
                    if (descriptorGroup.isAssignableFrom(requestedGroup)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private Set<Class<?>> effectiveGroups() {
            Set<Class<?>> effectiveGroups = new LinkedHashSet<>(groups);
            if (groups.contains(jakarta.validation.groups.Default.class)) {
                GroupSequence groupSequence = localDeclaringClass().getAnnotation(GroupSequence.class);
                if (groupSequence != null) {
                    for (Class<?> group : groupSequence.value()) {
                        if (group != localDeclaringClass()) {
                            effectiveGroups.add(group);
                        }
                    }
                }
            }
            return effectiveGroups;
        }

        private Class<?> localDeclaringClass() {
            return properties.get(0).declaringClass();
        }
    }

    private record ReflectionContainerElementDescriptor(
        Class<?> containerType,
        int typeArgumentIndex,
        Class<?> elementClass,
        List<ReflectionContainerElement> containerElements
    ) implements ContainerElementTypeDescriptor, ElementDescriptor.ConstraintFinder {

        @Override
        public Class<?> getContainerClass() {
            return containerType;
        }

        @Override
        public Integer getTypeArgumentIndex() {
            return typeArgumentIndex;
        }

        @Override
        public boolean isCascaded() {
            return containerElements.stream().anyMatch(ReflectionContainerElement::cascaded);
        }

        @Override
        public Set<GroupConversionDescriptor> getGroupConversions() {
            return containerElements.stream()
                .flatMap(containerElement -> containerElement.groupConversions.stream())
                .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
            return containerElementDescriptors(containerElements.stream()
                .flatMap(containerElement -> containerElement.nestedContainerElements.stream())
                .toList());
        }

        @Override
        public boolean hasConstraints() {
            return containerElements.stream().anyMatch(containerElement -> !containerElement.constraints.isEmpty());
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
            return containerElements.stream()
                .flatMap(containerElement -> containerElement.constraints.stream())
                .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public ConstraintFinder findConstraints() {
            return this;
        }
    }

    private static final class ReflectionConstraintDescriptor<A extends Annotation> implements ConstraintDescriptor<A> {

        private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPERS = Map.of(
            int.class, Integer.class,
            long.class, Long.class,
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            short.class, Short.class,
            float.class, Float.class,
            double.class, Double.class,
            char.class, Character.class
        );

        private final A annotation;
        private final Class<A> type;
        private final Set<Class<?>> groups;
        private final Set<Class<? extends Payload>> payload;
        private final List<Class<? extends jakarta.validation.ConstraintValidator<A, ?>>> validators;
        private final Map<CharSequence, Object> members;
        private final AnnotationValue<A> annotationValue;
        private final List<ReflectionConstraintDescriptor<?>> composingConstraints;
        private final boolean hasValidationAppliesTo;
        private final @Nullable Class<?> implicitGroup;

        @SuppressWarnings({"unchecked", "rawtypes"})
        private ReflectionConstraintDescriptor(A annotation) {
            this(annotation, null);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private ReflectionConstraintDescriptor(A annotation, @Nullable Class<?> implicitGroup) {
            this(annotation, implicitGroup, List.of());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private ReflectionConstraintDescriptor(A annotation,
                                               @Nullable Class<?> implicitGroup,
                                               List<ValidationMetadataProvider> metadataProviders) {
            this.annotation = annotation;
            this.type = (Class<A>) annotation.annotationType();
            ReflectionConstraintDefinitions.validate(type);
            this.members = annotationMembers(annotation, false);
            this.groups = groups(annotation, implicitGroup);
            this.implicitGroup = implicitGroup(annotation, implicitGroup);
            this.payload = Set.of((Class<? extends Payload>[]) readMember(annotation, "payload", new Class<?>[0]));
            this.validators = validatorClasses(type, metadataProviders);
            this.annotationValue = new AnnotationValue<>(type.getName(), members, annotationMembers(annotation, true));
            this.composingConstraints = composingConstraints(annotation, groups, payload);
            this.hasValidationAppliesTo = hasMember(annotation.annotationType(), MEMBER_VALIDATION_APPLIES_TO);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private ReflectionConstraintDescriptor(A annotation,
                                               Set<Class<?>> groups,
                                               Set<Class<? extends Payload>> payload,
                                               Map<CharSequence, Object> annotationMembers) {
            this.annotation = annotationWithMembers(annotation, annotationMembers);
            this.type = (Class<A>) annotation.annotationType();
            ReflectionConstraintDefinitions.validate(type);
            this.members = Map.copyOf(annotationMembers);
            this.groups = groups;
            this.implicitGroup = null;
            this.payload = payload;
            this.validators = List.of((Class[]) type.getAnnotation(Constraint.class).validatedBy());
            this.annotationValue = new AnnotationValue<>(type.getName(), members, annotationMembers(annotation, true));
            this.composingConstraints = composingConstraints(this.annotation, groups, payload);
            this.hasValidationAppliesTo = hasMember(annotation.annotationType(), MEMBER_VALIDATION_APPLIES_TO);
        }

        @Override
        public A getAnnotation() {
            return annotation;
        }

        @Override
        public String getMessageTemplate() {
            return (String) readMember(annotation, "message", "{" + type.getName() + ".message}");
        }

        @Override
        public Set<Class<?>> getGroups() {
            return groups.isEmpty() ? Set.of(jakarta.validation.groups.Default.class) : groups;
        }

        @Override
        public Set<Class<? extends Payload>> getPayload() {
            return payload;
        }

        @Override
        public @Nullable ConstraintTarget getValidationAppliesTo() {
            if (!hasValidationAppliesTo) {
                return null;
            }
            return (ConstraintTarget) readMember(annotation, MEMBER_VALIDATION_APPLIES_TO, ConstraintTarget.IMPLICIT);
        }

        boolean hasValidationAppliesTo() {
            return hasValidationAppliesTo;
        }

        boolean matchesImplicitGroup(BeanValidationContext context) {
            return implicitGroup != null && context.groups().contains(implicitGroup);
        }

        boolean matchesImplicitGroup(Class<?> group) {
            return implicitGroup != null && implicitGroup == group;
        }

        @Override
        public List<Class<? extends jakarta.validation.ConstraintValidator<A, ?>>> getConstraintValidatorClasses() {
            return validators;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return members
                .entrySet()
                .stream()
                .collect(Collectors.toUnmodifiableMap(e -> e.getKey().toString(), Map.Entry::getValue));
        }

        @Override
        public Set<ConstraintDescriptor<?>> getComposingConstraints() {
            return Set.copyOf(composingConstraints);
        }

        @Override
        public boolean isReportAsSingleViolation() {
            return type.isAnnotationPresent(jakarta.validation.ReportAsSingleViolation.class);
        }

        @Override
        public ValidateUnwrappedValue getValueUnwrapping() {
            if (payload.contains(Unwrapping.Unwrap.class) && payload.contains(Unwrapping.Skip.class)) {
                throw new ConstraintDeclarationException("Payload declared with both " + Unwrapping.Unwrap.class.getName() + " and " + Unwrapping.Skip.class);
            }
            if (payload.contains(Unwrapping.Unwrap.class)) {
                return ValidateUnwrappedValue.UNWRAP;
            }
            if (payload.contains(Unwrapping.Skip.class)) {
                return ValidateUnwrappedValue.SKIP;
            }
            return ValidateUnwrappedValue.DEFAULT;
        }

        @Override
        public <U> U unwrap(Class<U> type) {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new ValidationException("Cannot unwrap " + getClass().getName() + " as " + type.getName());
        }

        Class<A> getType() {
            return type;
        }

        private static Object readMember(Annotation annotation, String member, Object defaultValue) {
            try {
                Method method = annotation.annotationType().getDeclaredMethod(member);
                method.setAccessible(true);
                return method.invoke(annotation);
            } catch (NoSuchMethodException e) {
                return defaultValue;
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ValidationException("Cannot read annotation member " + annotation.annotationType().getName() + "." + member, e);
            }
        }

        private static boolean hasMember(Class<? extends Annotation> annotationType, String member) {
            try {
                annotationType.getDeclaredMethod(member);
                return true;
            } catch (NoSuchMethodException e) {
                return false;
            }
        }

        private static Set<Class<?>> groups(Annotation annotation, @Nullable Class<?> implicitGroup) {
            Set<Class<?>> groups = new LinkedHashSet<>(List.of((Class<?>[]) readMember(annotation, MEMBER_GROUPS, new Class<?>[0])));
            Class<?> resolvedImplicitGroup = implicitGroup(annotation, implicitGroup);
            if (resolvedImplicitGroup != null) {
                groups.add(jakarta.validation.groups.Default.class);
                if (resolvedImplicitGroup.isInterface()) {
                    groups.add(resolvedImplicitGroup);
                }
            }
            return Set.copyOf(groups);
        }

        private static @Nullable Class<?> implicitGroup(Annotation annotation, @Nullable Class<?> implicitGroup) {
            if (implicitGroup == null) {
                return null;
            }
            Set<Class<?>> groups = new LinkedHashSet<>(List.of((Class<?>[]) readMember(annotation, MEMBER_GROUPS, new Class<?>[0])));
            return groups.isEmpty() || groups.contains(jakarta.validation.groups.Default.class) ? implicitGroup : null;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static <A extends Annotation> List<Class<? extends jakarta.validation.ConstraintValidator<A, ?>>> validatorClasses(
            Class<A> constraintType,
            List<ValidationMetadataProvider> metadataProviders) {
            List<Class<? extends jakarta.validation.ConstraintValidator<A, ?>>> validators = new ArrayList<>(
                (List) List.of(constraintType.getAnnotation(Constraint.class).validatedBy())
            );
            for (ValidationMetadataProvider metadataProvider : metadataProviders) {
                Optional<List<Class<? extends jakarta.validation.ConstraintValidator<A, ?>>>> configured =
                    metadataProvider.getConstraintValidatorClasses(constraintType, validators);
                if (configured.isPresent()) {
                    validators = configured.get();
                }
            }
            return List.copyOf(validators);
        }

        private static List<ReflectionConstraintDescriptor<?>> composingConstraints(
            Annotation parentAnnotation,
            Set<Class<?>> groups,
            Set<Class<? extends Payload>> payload) {
            List<ReflectionConstraintDescriptor<?>> constraints = new ArrayList<>();
            List<ComposingAnnotation> composingAnnotations = composingAnnotations(parentAnnotation.annotationType().getDeclaredAnnotations());
            validateCompositionTargets(parentAnnotation.annotationType(), composingAnnotations);
            for (ComposingAnnotation composingAnnotation : composingAnnotations) {
                Map<CharSequence, Object> members = annotationMembers(composingAnnotation.annotation, false);
                applyOverrides(parentAnnotation, composingAnnotation, composingAnnotations, members);
                propagateValidationAppliesTo(parentAnnotation, composingAnnotation, members);
                members.put(MEMBER_GROUPS, groups.toArray(Class<?>[]::new));
                members.put("payload", payload.toArray(Class<?>[]::new));
                constraints.add(new ReflectionConstraintDescriptor(composingAnnotation.annotation, groups, payload, members));
            }
            return List.copyOf(constraints);
        }

        private static List<ComposingAnnotation> composingAnnotations(Annotation[] annotations) {
            Map<Class<? extends Annotation>, Boolean> containerTypes = new LinkedHashMap<>();
            Map<Class<? extends Annotation>, Boolean> directTypes = new LinkedHashMap<>();
            List<ComposingAnnotation> composingAnnotations = new ArrayList<>();
            for (Annotation annotation : annotations) {
                Class<? extends Annotation> annotationType = annotation.annotationType();
                if (annotationType.isAnnotationPresent(Constraint.class)) {
                    if (containerTypes.containsKey(annotationType)) {
                        throw new ConstraintDeclarationException("Cannot mix direct and container composing constraints for " + annotationType.getName());
                    }
                    directTypes.put(annotationType, true);
                    composingAnnotations.add(new ComposingAnnotation(annotation, -1));
                    continue;
                }
                List<Annotation> containedAnnotations = containedConstraintAnnotations(annotation);
                if (!containedAnnotations.isEmpty()) {
                    Class<? extends Annotation> containedType = containedAnnotations.get(0).annotationType();
                    if (directTypes.containsKey(containedType)) {
                        throw new ConstraintDeclarationException("Cannot mix direct and container composing constraints for " + containedType.getName());
                    }
                    containerTypes.put(containedType, true);
                    for (int i = 0; i < containedAnnotations.size(); i++) {
                        composingAnnotations.add(new ComposingAnnotation(containedAnnotations.get(i), i));
                    }
                }
            }
            return composingAnnotations;
        }

        private static void validateCompositionTargets(Class<? extends Annotation> parentType,
                                                       List<ComposingAnnotation> composingAnnotations) {
            if (composingAnnotations.isEmpty()) {
                return;
            }
            Set<ValidationTarget> commonTargets = EnumSet.copyOf(validationTargets(parentType));
            for (ComposingAnnotation composingAnnotation : composingAnnotations) {
                commonTargets.retainAll(validationTargets(composingAnnotation.type()));
                if (commonTargets.isEmpty()) {
                    throw new ConstraintDefinitionException("Composing constraints must share a compatible validation target: " + parentType.getName());
                }
            }
        }

        private static Set<ValidationTarget> validationTargets(Class<? extends Annotation> annotationType) {
            Constraint constraint = annotationType.getAnnotation(Constraint.class);
            if (constraint == null || constraint.validatedBy().length == 0) {
                return Set.of(ValidationTarget.ANNOTATED_ELEMENT, ValidationTarget.PARAMETERS);
            }
            Set<ValidationTarget> validationTargets = EnumSet.noneOf(ValidationTarget.class);
            for (Class<? extends jakarta.validation.ConstraintValidator<?, ?>> validator : constraint.validatedBy()) {
                SupportedValidationTarget supportedValidationTarget = validator.getAnnotation(SupportedValidationTarget.class);
                if (supportedValidationTarget == null) {
                    validationTargets.add(ValidationTarget.ANNOTATED_ELEMENT);
                } else {
                    validationTargets.addAll(Arrays.asList(supportedValidationTarget.value()));
                }
            }
            return validationTargets;
        }

        private static List<Annotation> containedConstraintAnnotations(Annotation container) {
            try {
                Method valueMethod = container.annotationType().getDeclaredMethod(MEMBER_VALUE);
                if (!valueMethod.getReturnType().isArray() || !Annotation.class.isAssignableFrom(valueMethod.getReturnType().getComponentType())) {
                    return List.of();
                }
                valueMethod.setAccessible(true);
                Annotation[] annotations = (Annotation[]) valueMethod.invoke(container);
                return Arrays.stream(annotations)
                    .filter(annotation -> annotation.annotationType().isAnnotationPresent(Constraint.class))
                    .toList();
            } catch (NoSuchMethodException e) {
                return List.of();
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ValidationException("Cannot read constraint container " + container.annotationType().getName(), e);
            }
        }

        private static void applyOverrides(
            Annotation parentAnnotation,
            ComposingAnnotation composingAnnotation,
            List<ComposingAnnotation> composingAnnotations,
            Map<CharSequence, Object> members) {
            Map<CharSequence, Object> parentMembers = annotationMembers(parentAnnotation, false);
            for (Method method : parentAnnotation.annotationType().getDeclaredMethods()) {
                Object value = parentMembers.get(method.getName());
                if (value == null) {
                    continue;
                }
                OverridesAttribute override = method.getAnnotation(OverridesAttribute.class);
                if (override != null) {
                    applyOverride(composingAnnotation, composingAnnotations, members, method, value, override);
                }
                OverridesAttribute.List overrides = method.getAnnotation(OverridesAttribute.List.class);
                if (overrides != null) {
                    for (OverridesAttribute listedOverride : overrides.value()) {
                        applyOverride(composingAnnotation, composingAnnotations, members, method, value, listedOverride);
                    }
                }
            }
        }

        private static void propagateValidationAppliesTo(Annotation parentAnnotation,
                                                         ComposingAnnotation composingAnnotation,
                                                         Map<CharSequence, Object> members) {
            ConstraintTarget validationAppliesTo = (ConstraintTarget) readMember(parentAnnotation, MEMBER_VALIDATION_APPLIES_TO, ConstraintTarget.IMPLICIT);
            if (validationAppliesTo != ConstraintTarget.IMPLICIT && hasMember(composingAnnotation.type(), MEMBER_VALIDATION_APPLIES_TO)) {
                members.put(MEMBER_VALIDATION_APPLIES_TO, validationAppliesTo);
            }
        }

        private static void applyOverride(
            ComposingAnnotation composingAnnotation,
            List<ComposingAnnotation> composingAnnotations,
            Map<CharSequence, Object> members,
            Method method,
            Object value,
            OverridesAttribute override) {
            if (override.constraint() == composingAnnotation.type()
                && (override.constraintIndex() < 0 || override.constraintIndex() == composingAnnotation.index)) {
                String name = override.name().isEmpty() ? method.getName() : override.name();
                validateOverride(method, value, composingAnnotation.type(), name);
                members.put(name, value);
            } else if (override.constraint() == composingAnnotation.type()
                && override.constraintIndex() >= composingAnnotations.stream()
                    .filter(annotation -> annotation.type() == composingAnnotation.type())
                    .count()) {
                throw new ConstraintDefinitionException("Invalid constraintIndex " + override.constraintIndex() + " for " + composingAnnotation.type().getName());
            }
        }

        private static void validateOverride(Method method,
                                             Object value,
                                             Class<? extends Annotation> composingType,
                                             String memberName) {
            Method composingMember;
            try {
                composingMember = composingType.getDeclaredMethod(memberName);
            } catch (NoSuchMethodException e) {
                throw new ConstraintDefinitionException("Cannot override missing member " + composingType.getName() + "." + memberName, e);
            }
            if (value != null && !isAssignableToMember(value.getClass(), composingMember.getReturnType())) {
                throw new ConstraintDefinitionException("Override member " + method.getName() + " is not assignable to " + composingType.getName() + "." + memberName);
            }
        }

        private static boolean isAssignableToMember(Class<?> valueType, Class<?> memberType) {
            if (!memberType.isPrimitive()) {
                return memberType.isAssignableFrom(valueType);
            }
            return PRIMITIVE_WRAPPERS.get(memberType) == valueType;
        }

        @SuppressWarnings("unchecked")
        private static <A extends Annotation> A annotationWithMembers(A annotation,
                                                                      Map<CharSequence, Object> members) {
            Map<String, Object> values = members.entrySet()
                .stream()
                .collect(Collectors.toMap(e -> e.getKey().toString(), Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
            return (A) Proxy.newProxyInstance(
                annotation.annotationType().getClassLoader(),
                new Class<?>[] { annotation.annotationType() },
                (proxy, method, args) -> annotationMemberInvocation(annotation, values, proxy, method, args)
            );
        }

        private static Object annotationMemberInvocation(Annotation annotation,
                                                         Map<String, Object> values,
                                                         Object proxy,
                                                         Method method,
                                                         @Nullable Object[] args) throws ReflectiveOperationException {
            if (method.getParameterCount() == 0) {
                Object value = noArgAnnotationMember(annotation, values, method);
                if (value != null) {
                    return value;
                }
            }
            if ("equals".equals(method.getName()) && method.getParameterCount() == 1) {
                return args != null && proxy == args[0];
            }
            return method.invoke(annotation, args);
        }

        @Nullable
        private static Object noArgAnnotationMember(Annotation annotation,
                                                    Map<String, Object> values,
                                                    Method method) {
            return switch (method.getName()) {
                case "annotationType" -> annotation.annotationType();
                case "toString" -> annotation.annotationType().getName() + values;
                case "hashCode" -> values.hashCode();
                default -> values.getOrDefault(method.getName(), method.getDefaultValue());
            };
        }

        private record ComposingAnnotation(
            Annotation annotation,
            int index
        ) {
            Class<? extends Annotation> type() {
                return annotation.annotationType();
            }
        }
    }

    private record ReflectionConstraintViolation<T>(
        @Nullable T rootBean,
        @Nullable Class<T> rootBeanClass,
        @Nullable Object leafBean,
        @Nullable Object invalidValue,
        String message,
        String messageTemplate,
        jakarta.validation.Path propertyPath,
        ConstraintDescriptor<?> constraintDescriptor,
        Object @Nullable [] executableParameters,
        @Nullable Object executableReturnValue
    ) implements ConstraintViolation<T> {

        ReflectionConstraintViolation(@Nullable T rootBean,
                                      @Nullable Class<T> rootBeanClass,
                                      @Nullable Object leafBean,
                                      @Nullable Object invalidValue,
                                      String message,
                                      String messageTemplate,
                                      jakarta.validation.Path propertyPath,
                                      ConstraintDescriptor<?> constraintDescriptor) {
            this(rootBean, rootBeanClass, leafBean, invalidValue, message, messageTemplate, propertyPath, constraintDescriptor, null, null);
        }

        ReflectionConstraintViolation<T> withExecutableParameters(Object[] executableParameters) {
            return new ReflectionConstraintViolation<>(
                rootBean,
                rootBeanClass,
                leafBean,
                invalidValue,
                message,
                messageTemplate,
                propertyPath,
                constraintDescriptor,
                executableParameters,
                null
            );
        }

        ReflectionConstraintViolation<T> withExecutableReturnValue(@Nullable Object executableReturnValue) {
            return new ReflectionConstraintViolation<>(
                rootBean,
                rootBeanClass,
                leafBean,
                invalidValue,
                message,
                messageTemplate,
                propertyPath,
                constraintDescriptor,
                null,
                executableReturnValue
            );
        }

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
            return executableParameters;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof ReflectionConstraintViolation<?> other)) {
                return false;
            }
            return Objects.equals(rootBean, other.rootBean)
                && Objects.equals(rootBeanClass, other.rootBeanClass)
                && Objects.equals(leafBean, other.leafBean)
                && Objects.equals(invalidValue, other.invalidValue)
                && Objects.equals(message, other.message)
                && Objects.equals(messageTemplate, other.messageTemplate)
                && Objects.equals(propertyPath, other.propertyPath)
                && Objects.equals(constraintDescriptor, other.constraintDescriptor)
                && Arrays.equals(executableParameters, other.executableParameters)
                && Objects.equals(executableReturnValue, other.executableReturnValue);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(
                rootBean,
                rootBeanClass,
                leafBean,
                invalidValue,
                message,
                messageTemplate,
                propertyPath,
                constraintDescriptor,
                executableReturnValue
            );
            result = 31 * result + Arrays.hashCode(executableParameters);
            return result;
        }

        @Override
        public String toString() {
            return "ReflectionConstraintViolation[" +
                "rootBean=" + rootBean +
                ", rootBeanClass=" + rootBeanClass +
                ", leafBean=" + leafBean +
                ", invalidValue=" + invalidValue +
                ", message=" + message +
                ", messageTemplate=" + messageTemplate +
                ", propertyPath=" + propertyPath +
                ", constraintDescriptor=" + constraintDescriptor +
                ", executableParameters=" + Arrays.toString(executableParameters) +
                ", executableReturnValue=" + executableReturnValue +
                ']';
        }

        @Override
        public Object getExecutableReturnValue() {
            return executableReturnValue;
        }

        @Override
        public jakarta.validation.Path getPropertyPath() {
            return propertyPath;
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
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new ValidationException("Cannot unwrap " + getClass().getName() + " as " + type.getName());
        }
    }

    private record ReflectionPath(@Nullable String propertyName) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            if (propertyName == null) {
                return List.<Node>of(new ReflectionBeanNode()).iterator();
            }
            return List.<Node>of(new ReflectionNode(propertyName)).iterator();
        }

        @Override
        public String toString() {
            return propertyName == null ? "" : propertyName;
        }
    }

    private static final class ReflectionBeanNode implements ReflectionPlainBeanPathNode {

        @Override
        public ElementKind getKind() {
            return ElementKind.BEAN;
        }
    }

    private record ReflectionReturnValueExecutablePath(Method method) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            return List.<Node>of(
                new ReflectionMethodNode(method),
                new ReflectionReturnValueNode()
            ).iterator();
        }

        @Override
        public String toString() {
            return method.getName() + ".<return value>";
        }
    }

    private record ReflectionReturnValueContainerElementPath(Method method,
                                                             ReflectionContainerContext containerContext) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            if (containerContext.nodeName() == null) {
                return List.<Node>of(
                    new ReflectionMethodNode(method),
                    new ReflectionReturnValueNode()
                ).iterator();
            }
            return List.<Node>of(
                new ReflectionMethodNode(method),
                new ReflectionReturnValueNode(),
                new ReflectionContainerElementNode(containerContext)
            ).iterator();
        }

        @Override
        public String toString() {
            return method.getName() + ".<return value>." + containerContext.nodeName();
        }
    }

    private record ReflectionAppendedPropertyPath(jakarta.validation.Path parent,
                                                  String propertyName) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            List<Node> nodes = new ArrayList<>();
            if (parent instanceof ReflectionCascadedContainerElementPath cascadedContainerElementPath) {
                cascadedContainerElementPath.parent.iterator().forEachRemaining(nodes::add);
                ReflectionContainerContext containerContext = cascadedContainerElementPath.containerContext;
                nodes.add(new ReflectionContainerPropertyNode(
                    propertyName,
                    containerContext.iterable(),
                    containerContext.key(),
                    containerContext.index(),
                    containerContext.containerClass(),
                    containerContext.typeArgumentIndex()
                ));
            } else {
                parent.iterator().forEachRemaining(nodes::add);
                nodes.add(new ReflectionNode(propertyName));
            }
            return nodes.iterator();
        }

        @Override
        public String toString() {
            return parent + "." + propertyName;
        }
    }

    private record ReflectionNestedContainerElementPath(jakarta.validation.Path parent,
                                                        ReflectionContainerContext containerContext) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            List<Node> nodes = new ArrayList<>();
            parent.iterator().forEachRemaining(nodes::add);
            if (containerContext.nodeName() != null) {
                nodes.add(new ReflectionContainerElementNode(containerContext));
            }
            return nodes.iterator();
        }

        @Override
        public String toString() {
            return parent + "." + containerContext.nodeName();
        }
    }

    private record ReflectionCascadedContainerElementPath(jakarta.validation.Path parent,
                                                          ReflectionContainerContext containerContext) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            List<Node> nodes = new ArrayList<>();
            parent.iterator().forEachRemaining(nodes::add);
            nodes.add(new ReflectionContainerBeanNode(containerContext));
            return nodes.iterator();
        }

        @Override
        public String toString() {
            return parent + "." + containerContext.nodeName();
        }
    }

    private record ReflectionMethodExecutablePath(Method method) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            return List.<Node>of(new ReflectionMethodNode(method)).iterator();
        }

        @Override
        public String toString() {
            return method.getName();
        }
    }

    private record ReflectionConstructorPath(Constructor<?> constructor) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            return List.<Node>of(new ReflectionConstructorNode(constructor)).iterator();
        }

        @Override
        public String toString() {
            return constructor.getDeclaringClass().getSimpleName();
        }
    }

    private record ReflectionConstructorCrossParameterPath(Constructor<?> constructor) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            return List.<Node>of(
                new ReflectionConstructorNode(constructor),
                new ReflectionCrossParameterNode()
            ).iterator();
        }

        @Override
        public String toString() {
            return constructor.getDeclaringClass().getSimpleName() + ".<cross-parameter>";
        }
    }

    private record ReflectionMethodCrossParameterPath(Method method) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            return List.<Node>of(
                new ReflectionMethodNode(method),
                new ReflectionCrossParameterNode()
            ).iterator();
        }

        @Override
        public String toString() {
            return method.getName() + ".<cross-parameter>";
        }
    }

    private record ReflectionConstructorExecutablePath(Constructor<?> constructor, String parameterName, int parameterIndex) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            return List.<Node>of(
                new ReflectionConstructorNode(constructor),
                new ReflectionParameterNode(parameterName, parameterIndex)
            ).iterator();
        }

        @Override
        public String toString() {
            return constructor.getDeclaringClass().getSimpleName() + "." + parameterName;
        }
    }

    private record ReflectionConstructorParameterContainerElementPath(Constructor<?> constructor,
                                                                      String parameterName,
                                                                      int parameterIndex,
                                                                      ReflectionContainerContext containerContext) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            if (containerContext.nodeName() == null) {
                return List.<Node>of(
                    new ReflectionConstructorNode(constructor),
                    new ReflectionParameterNode(parameterName, parameterIndex)
                ).iterator();
            }
            return List.<Node>of(
                new ReflectionConstructorNode(constructor),
                new ReflectionParameterNode(parameterName, parameterIndex),
                new ReflectionContainerElementNode(containerContext)
            ).iterator();
        }

        @Override
        public String toString() {
            return constructor.getDeclaringClass().getSimpleName() + "." + parameterName + "." + containerContext.nodeName();
        }
    }

    private record ReflectionExecutablePath(Method method, String parameterName, int parameterIndex) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            return List.<Node>of(
                new ReflectionMethodNode(method),
                new ReflectionParameterNode(parameterName, parameterIndex)
            ).iterator();
        }

        @Override
        public String toString() {
            return method.getName() + "." + parameterName;
        }
    }

    private record ReflectionParameterContainerElementPath(Method method,
                                                           String parameterName,
                                                           int parameterIndex,
                                                           ReflectionContainerContext containerContext) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            if (containerContext.nodeName() == null) {
                return List.<Node>of(
                    new ReflectionMethodNode(method),
                    new ReflectionParameterNode(parameterName, parameterIndex)
                ).iterator();
            }
            return List.<Node>of(
                new ReflectionMethodNode(method),
                new ReflectionParameterNode(parameterName, parameterIndex),
                new ReflectionContainerElementNode(containerContext)
            ).iterator();
        }

        @Override
        public String toString() {
            return method.getName() + "." + parameterName + "." + containerContext.nodeName();
        }
    }

    private record ReflectionConstructorReturnValueExecutablePath(Constructor<?> constructor) implements jakarta.validation.Path {

        @Override
        public Iterator<Node> iterator() {
            return List.<Node>of(
                new ReflectionConstructorNode(constructor),
                new ReflectionReturnValueNode()
            ).iterator();
        }

        @Override
        public String toString() {
            return constructor.getDeclaringClass().getSimpleName() + ".<return value>";
        }
    }

    private record ReflectionNode(@Nullable String name,
                                  boolean inIterable,
                                  @Nullable Object key,
                                  @Nullable Integer index,
                                  @Nullable Class<?> containerClass,
                                  @Nullable Integer typeArgumentIndex) implements jakarta.validation.Path.PropertyNode {

        private ReflectionNode(@Nullable String name) {
            this(name, false, null, null, null, null);
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.PROPERTY;
        }

        @Override
        public boolean isInIterable() {
            return inIterable;
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
        public String getName() {
            return name;
        }

        @Override
        public <T extends Path.Node> T as(Class<T> nodeType) {
            return nodeType.cast(this);
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
        public String toString() {
            return name;
        }
    }

    private static final class ReflectionReturnValueNode implements Path.ReturnValueNode, ReflectionPlainPathNode {

        @Override
        public String getName() {
            return "<return value>";
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.RETURN_VALUE;
        }

        @Override
        public String toString() {
            return "<return value>";
        }
    }

    private static final class ReflectionCrossParameterNode implements Path.CrossParameterNode, ReflectionPlainPathNode {

        @Override
        public String getName() {
            return "<cross-parameter>";
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.CROSS_PARAMETER;
        }

        @Override
        public String toString() {
            return "<cross-parameter>";
        }
    }

    private record ReflectionConstructorNode(Constructor<?> constructor) implements Path.ConstructorNode, ReflectionPlainPathNode {

        @Override
        public String getName() {
            return constructor.getDeclaringClass().getSimpleName();
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.CONSTRUCTOR;
        }

        @Override
        public List<Class<?>> getParameterTypes() {
            return List.of(constructor.getParameterTypes());
        }

        @Override
        public String toString() {
            return constructor.getDeclaringClass().getSimpleName();
        }
    }

    private record ReflectionMethodNode(Method method) implements Path.MethodNode, ReflectionPlainPathNode {

        @Override
        public String getName() {
            return method.getName();
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.METHOD;
        }

        @Override
        public List<Class<?>> getParameterTypes() {
            return List.of(method.getParameterTypes());
        }

        @Override
        public String toString() {
            return method.getName();
        }
    }

    private record ReflectionParameterNode(String name, int parameterIndex) implements Path.ParameterNode, ReflectionPlainPathNode {

        @Override
        public ElementKind getKind() {
            return ElementKind.PARAMETER;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getParameterIndex() {
            return parameterIndex;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record InterpolationContext(
        ConstraintDescriptor<?> constraintDescriptor,
        @Nullable Object validatedValue
    ) implements MessageInterpolator.Context {

        @Override
        public ConstraintDescriptor<?> getConstraintDescriptor() {
            return constraintDescriptor;
        }

        @Override
        public Object getValidatedValue() {
            return validatedValue;
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new ValidationException("Cannot unwrap " + getClass().getName() + " as " + type.getName());
        }
    }

}
