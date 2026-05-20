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
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ClockProvider;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.OverridesAttribute;
import jakarta.validation.Payload;
import jakarta.validation.Path;
import jakarta.validation.UnexpectedTypeException;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ConstructorDescriptor;
import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.ElementDescriptor;
import jakarta.validation.metadata.GroupConversionDescriptor;
import jakarta.validation.metadata.MethodDescriptor;
import jakarta.validation.metadata.MethodType;
import jakarta.validation.metadata.PropertyDescriptor;
import jakarta.validation.metadata.Scope;
import jakarta.validation.metadata.ValidateUnwrappedValue;
import jakarta.validation.valueextraction.Unwrapping;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Opt-in reflection fallback validator used by the Jakarta Validation compliance stack.
 *
 * @since 5.1
 */
@Singleton
@Primary
@Replaces(DefaultValidator.class)
@Requires(property = ReflectionValidator.ENABLED, notEquals = StringUtils.FALSE, defaultValue = StringUtils.TRUE)
public class ReflectionValidator extends DefaultValidator {

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

    private final ValidatorConfiguration configuration;
    private final MessageInterpolator messageInterpolator;
    private final ClockProvider clockProvider;
    private final ValueExtractorRegistry valueExtractorRegistry;
    private final boolean warningsEnabled;

    /**
     * @param configuration The validator configuration
     */
    public ReflectionValidator(ValidatorConfiguration configuration) {
        this(configuration, true);
    }

    /**
     * @param configuration The validator configuration
     * @param warningsEnabled Whether reflection warnings are enabled
     */
    @Inject
    public ReflectionValidator(ValidatorConfiguration configuration,
                               @Property(name = WARNINGS_ENABLED, defaultValue = StringUtils.TRUE) boolean warningsEnabled) {
        super(configuration);
        this.configuration = configuration;
        this.messageInterpolator = configuration.getMessageInterpolator();
        this.clockProvider = configuration.getClockProvider();
        this.valueExtractorRegistry = configuration.getValueExtractorRegistry();
        this.warningsEnabled = warningsEnabled;
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groups) {
        requireNonNull("object", object);
        BeanValidationContext context = BeanValidationContext.fromGroups(groups);
        if (ReflectionGroupSequences.hasInheritedDefaultGroupSequence(object.getClass(), context)) {
            return validateReflectivelyWithInheritedDefaultGroupSequence(object, getBeanIntrospection(object) != null);
        }
        BeanIntrospection<T> introspection = getBeanIntrospection(object);
        if (introspection != null) {
            Set<ConstraintViolation<T>> reflected = validateReflectively(object, context, true);
            Set<ConstraintViolation<T>> existing = super.validate(object, groups);
            return mergeViolations(existing, reflected);
        }
        return validateReflectively(object, context, false);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validate(T object, BeanValidationContext validationContext) {
        requireNonNull("object", object);
        BeanValidationContext context = validationContext == null ? BeanValidationContext.DEFAULT : validationContext;
        if (ReflectionGroupSequences.hasInheritedDefaultGroupSequence(object.getClass(), context)) {
            return validateReflectivelyWithInheritedDefaultGroupSequence(object, getBeanIntrospection(object) != null);
        }
        BeanIntrospection<T> introspection = getBeanIntrospection(object);
        if (introspection != null) {
            Set<ConstraintViolation<T>> reflected = validateReflectively(object, context, true);
            return mergeViolations(super.validate(object, context), reflected);
        }
        return validateReflectively(object, context, false);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName, BeanValidationContext context) {
        requireNonNull("object", object);
        requireNonEmpty("propertyName", propertyName);
        BeanIntrospection<T> introspection = getBeanIntrospection(object);
        if (introspection != null) {
            BeanValidationContext validationContext = context == null ? BeanValidationContext.DEFAULT : context;
            ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(object.getClass());
            if (metadata.properties.containsKey(propertyName)) {
                return validatePropertiesReflectively(object, propertyName, validationContext, metadata);
            }
            return super.validateProperty(object, propertyName, validationContext);
        }
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(object.getClass());
        List<ReflectionProperty> properties = metadata.properties.get(propertyName);
        if (properties == null) {
            throw new IllegalArgumentException("No property [" + propertyName + "] found on type: " + object.getClass());
        }
        return validatePropertiesReflectively(object, propertyName, context == null ? BeanValidationContext.DEFAULT : context, metadata);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateValue(Class<T> beanType, String propertyName, @Nullable Object value, BeanValidationContext context) {
        requireNonNull("beanType", beanType);
        requireNonEmpty("propertyName", propertyName);
        BeanIntrospection<T> introspection = getBeanIntrospection(beanType);
        if (introspection != null) {
            BeanValidationContext validationContext = context == null ? BeanValidationContext.DEFAULT : context;
            ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(beanType);
            if (metadata.properties.containsKey(propertyName)) {
                return validateValueReflectively(beanType, propertyName, value, validationContext, metadata);
            }
            return super.validateValue(beanType, propertyName, value, validationContext);
        }
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(beanType);
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
        if (getBeanIntrospection(clazz) != null) {
            return super.getConstraintsForClass(clazz);
        }
        return ReflectionBeanMetadata.of(clazz);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateParameters(T object, Method method, Object[] parameterValues, Class<?>... groups) {
        requireNonNull("object", object);
        requireNonNull("method", method);
        requireNonNull("parameterValues", parameterValues);
        requireNonNull("groups", groups);
        Set<ConstraintViolation<T>> violations = super.validateParameters(object, method, parameterValues, groups);
        if (!violations.isEmpty()) {
            return violations;
        }
        return validateParametersReflectively(object, method, parameterValues, BeanValidationContext.fromGroups(groups));
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateReturnValue(T object, Method method, @Nullable Object returnValue, Class<?>... groups) {
        requireNonNull("object", object);
        requireNonNull("method", method);
        requireNonNull("groups", groups);
        Set<ConstraintViolation<T>> violations = super.validateReturnValue(object, method, returnValue, groups);
        if (!violations.isEmpty()) {
            return violations;
        }
        return validateReturnValueReflectively(object, method, returnValue, BeanValidationContext.fromGroups(groups));
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorParameters(Constructor<? extends T> constructor, Object[] parameterValues, Class<?>... groups) {
        requireNonNull("constructor", constructor);
        requireNonNull("parameterValues", parameterValues);
        requireNonNull("groups", groups);
        BeanIntrospection<? extends T> introspection = getBeanIntrospection(constructor.getDeclaringClass());
        if (introspection != null && introspection.getConstructorArguments().length == constructor.getParameterCount()) {
            Set<ConstraintViolation<T>> violations = super.validateConstructorParameters(constructor, parameterValues, groups);
            if (!violations.isEmpty()) {
                return violations;
            }
        }
        return validateConstructorParametersReflectively(constructor, parameterValues, BeanValidationContext.fromGroups(groups));
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateConstructorReturnValue(Constructor<? extends T> constructor,
                                                                          T createdObject,
                                                                          Class<?>... groups) {
        requireNonNull("constructor", constructor);
        requireNonNull("createdObject", createdObject);
        requireNonNull("groups", groups);
        return validateConstructorReturnValueReflectively(constructor, createdObject, BeanValidationContext.fromGroups(groups));
    }

    private <T> Set<ConstraintViolation<T>> validateReflectively(T object,
                                                                 BeanValidationContext context,
                                                                 boolean supplementIntrospection) {
        ReflectionGroupConversions.validateBean(object.getClass());
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(object.getClass());
        Set<Class<? extends Annotation>> generatedTypeConstraints = supplementIntrospection
            ? generatedTypeConstraints(object.getClass())
            : Set.of();
        warnOnce(object.getClass().getName(), "class", supplementIntrospection
            ? "supplementing Micronaut bean introspection with reflection metadata"
            : "validating without Micronaut bean introspection");
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        for (List<Class<?>> groupPass : ReflectionGroupSequences.validationGroupPasses(object.getClass(), context)) {
            int violationCount = violations.size();
            BeanValidationContext groupContext = BeanValidationContext.fromGroups(groupPass.toArray(Class<?>[]::new));
            validateConstraints(
                object,
                object.getClass(),
                object,
                object,
                object.getClass(),
                supplementalConstraints(metadata.constraints, generatedTypeConstraints, object.getClass()),
                groupContext,
                violations,
                new ReflectionPath(null)
            );
            for (List<ReflectionProperty> properties : metadata.properties.values()) {
                boolean suppressGeneratedPropertyConstraints = supplementIntrospection && properties.size() == 1;
                for (ReflectionProperty property : properties) {
                    validateProperty(object, object, property, groupContext, violations, suppressGeneratedPropertyConstraints, true);
                }
            }
            if (violations.size() > violationCount) {
                break;
            }
        }
        return Collections.unmodifiableSet(violations);
    }

    private <T> Set<ConstraintViolation<T>> validateReflectivelyWithInheritedDefaultGroupSequence(T object,
                                                                                                  boolean supplementIntrospection) {
        ReflectionGroupConversions.validateBean(object.getClass());
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(object.getClass());
        warnOnce(object.getClass().getName(), "class", supplementIntrospection
            ? "supplementing Micronaut bean introspection with reflection metadata"
            : "validating without Micronaut bean introspection");
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        validateReflectionGroupPass(object, metadata, BeanValidationContext.fromGroups(object.getClass()), violations, supplementIntrospection);
        for (List<Class<?>> groupPass : ReflectionGroupSequences.inheritedDefaultGroupSequencePasses(object.getClass())) {
            int violationCount = violations.size();
            BeanValidationContext groupContext = BeanValidationContext.fromGroups(groupPass.toArray(Class<?>[]::new));
            validateReflectionGroupPass(object, metadata, groupContext, violations, supplementIntrospection);
            if (violations.size() > violationCount) {
                break;
            }
        }
        return Collections.unmodifiableSet(violations);
    }

    private <T> void validateReflectionGroupPass(T object,
                                                 ReflectionBeanMetadata metadata,
                                                 BeanValidationContext groupContext,
                                                 Set<ConstraintViolation<T>> violations,
                                                 boolean supplementIntrospection) {
        Set<Class<? extends Annotation>> generatedTypeConstraints = supplementIntrospection
            ? generatedTypeConstraints(object.getClass())
            : Set.of();
        validateConstraints(
            object,
            object.getClass(),
            object,
            object,
            object.getClass(),
            supplementalConstraints(metadata.constraints, generatedTypeConstraints, object.getClass()),
            groupContext,
            violations,
            new ReflectionPath(null)
        );
        for (List<ReflectionProperty> properties : metadata.properties.values()) {
            boolean suppressGeneratedPropertyConstraints = supplementIntrospection && properties.size() == 1;
            for (ReflectionProperty property : properties) {
                validateProperty(object, object, property, groupContext, violations, suppressGeneratedPropertyConstraints, true);
            }
        }
    }

    private Set<Class<? extends Annotation>> generatedTypeConstraints(Class<?> beanType) {
        BeanDescriptor descriptor = super.getConstraintsForClass(beanType);
        return descriptor.getConstraintDescriptors()
            .stream()
            .map(constraintDescriptor -> constraintDescriptor.getAnnotation().annotationType())
            .collect(Collectors.toUnmodifiableSet());
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
        Set<Class<? extends Annotation>> generatedConstraints,
        Class<?> valueType) {
        if (generatedConstraints.isEmpty()) {
            return constraints;
        }
        return constraints.stream()
            .filter(constraint -> !generatedConstraints.contains(constraint.getAnnotation().annotationType())
                || hasAmbiguousValidatorResolution(constraint, valueType))
            .toList();
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
                && !hasAmbiguousValidatorResolution(constraint, valueType)) {
                remainingGeneratedConstraints.put(key, remaining - 1);
            } else {
                supplemental.add(constraint);
            }
        }
        return supplemental;
    }

    private <T> Set<ConstraintViolation<T>> validatePropertyReflectively(T object,
                                                                         String propertyName,
                                                                         BeanValidationContext context) {
        return validatePropertiesReflectively(object, propertyName, context, ReflectionBeanMetadata.of(object.getClass()));
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
        for (List<Class<?>> groupPass : ReflectionGroupSequences.validationGroupPasses(object.getClass(), context)) {
            int violationCount = violations.size();
            BeanValidationContext groupContext = BeanValidationContext.fromGroups(groupPass.toArray(Class<?>[]::new));
            for (ReflectionProperty property : properties) {
                validateProperty(object, object, property, groupContext, violations, false, true);
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
        for (List<Class<?>> groupPass : ReflectionGroupSequences.validationGroupPasses(beanType, context)) {
            int violationCount = violations.size();
            BeanValidationContext groupContext = BeanValidationContext.fromGroups(groupPass.toArray(Class<?>[]::new));
            for (ReflectionProperty property : properties) {
                validateConstraints(null, beanType, null, value, property.type, property.constraints, groupContext, violations, new ReflectionPath(property.name));
            }
            if (violations.size() > violationCount) {
                break;
            }
        }
        return Collections.unmodifiableSet(violations);
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
        for (ConstraintViolation<T> violation : existing) {
            existingCounts.merge(ReflectionViolationKey.of(violation), 1, Integer::sum);
        }
        Set<ConstraintViolation<T>> merged = new LinkedHashSet<>(existing);
        for (ConstraintViolation<T> violation : reflected) {
            ReflectionViolationKey key = ReflectionViolationKey.of(violation);
            Integer remaining = existingCounts.get(key);
            if (remaining == null || remaining == 0) {
                merged.add(violation);
            } else {
                existingCounts.put(key, remaining - 1);
            }
        }
        return Collections.unmodifiableSet(merged);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> Set<ConstraintViolation<T>> validateReturnValueReflectively(T object,
                                                                            Method method,
                                                                            @Nullable Object returnValue,
                                                                            BeanValidationContext context) {
        warnOnce(method.getDeclaringClass().getName(), method.getName(), "validating executable return value without Micronaut executable metadata");
        ReflectionMethodDeclarations.validateReturnValueDeclarations(method);
        ReflectionGroupConversions.validateMethodReturnValueDeclarations(method);
        List<Method> methodHierarchy = ReflectionMethodDeclarations.hierarchy(method);
        List<ReflectionConstraintDescriptor<?>> constraints = methodHierarchy.stream()
            .flatMap(hierarchyMethod -> constraintsFor(hierarchyMethod).stream())
            .toList();
        List<ReflectionContainerElement> containerElements = containerElementsFor(method.getAnnotatedReturnType());
        boolean cascaded = ReflectionMethodDeclarations.hasCascadedReturnValueInHierarchy(method);
        if (constraints.isEmpty() && containerElements.isEmpty() && !cascaded) {
            return Collections.emptySet();
        }
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        for (ReflectionConstraintDescriptor constraint : constraints) {
            if (!isGroupIncluded(constraint, context)) {
                continue;
            }
            validateExecutableConstraintDeclaration(constraint, method);
            if (!appliesTo(constraint, ConstraintTarget.RETURN_VALUE)) {
                continue;
            }
            jakarta.validation.Path path = new ReflectionReturnValueExecutablePath(method);
            ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(clockProvider, object, constraint.getMessageTemplate(), path);
            Boolean valid = validateConstraint(constraint, returnValue, method.getReturnType(), validatorContext, ConstraintTarget.RETURN_VALUE, true);
            if (valid == null) {
                continue;
            }
            if (!valid && !validatorContext.defaultViolationDisabled()) {
                violations.add(new ReflectionConstraintViolation<>(
                    object,
                    (Class<T>) object.getClass(),
                    object,
                    returnValue,
                    interpolate(constraint.getMessageTemplate(), constraint, returnValue),
                    constraint.getMessageTemplate(),
                    path,
                    constraint
                ));
            }
            for (ReflectionConstraintValidatorContext.CustomViolation customViolation : validatorContext.customViolations()) {
                violations.add(new ReflectionConstraintViolation<>(
                    object,
                    (Class<T>) object.getClass(),
                    object,
                    returnValue,
                    interpolate(customViolation.messageTemplate(), constraint, returnValue),
                    customViolation.messageTemplate(),
                    customViolation.path(),
                    constraint
                ));
            }
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
            validateCascadedValue(
                object,
                object.getClass(),
                returnValue,
                returnValue,
                context,
                violations,
                new ReflectionReturnValueExecutablePath(method)
            );
        }
        return Collections.unmodifiableSet(violations);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> Set<ConstraintViolation<T>> validateConstructorReturnValueReflectively(Constructor<? extends T> constructor,
                                                                                       T createdObject,
                                                                                       BeanValidationContext context) {
        warnOnce(constructor.getDeclaringClass().getName(), constructor.getName(), "validating constructor return value without Micronaut executable metadata");
        ReflectionGroupConversions.validateConstructorReturnValueDeclaration(constructor);
        List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(constructor);
        boolean cascaded = constructor.isAnnotationPresent(Valid.class);
        if (constraints.isEmpty() && !cascaded) {
            return Collections.emptySet();
        }
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        for (ReflectionConstraintDescriptor constraint : constraints) {
            if (!isGroupIncluded(constraint, context)) {
                continue;
            }
            validateNonExecutableConstraintDeclaration(constraint);
            jakarta.validation.Path path = new ReflectionConstructorReturnValueExecutablePath(constructor);
            ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(clockProvider, null, constraint.getMessageTemplate(), path);
            Boolean valid = validateConstraint(constraint, createdObject, constructor.getDeclaringClass(), validatorContext, ConstraintTarget.IMPLICIT, true);
            if (valid == null) {
                continue;
            }
            if (!valid && !validatorContext.defaultViolationDisabled()) {
                violations.add(new ReflectionConstraintViolation<>(
                    null,
                    (Class<T>) constructor.getDeclaringClass(),
                    null,
                    createdObject,
                    interpolate(constraint.getMessageTemplate(), constraint, createdObject),
                    constraint.getMessageTemplate(),
                    path,
                    constraint
                ));
            }
            for (ReflectionConstraintValidatorContext.CustomViolation customViolation : validatorContext.customViolations()) {
                violations.add(new ReflectionConstraintViolation<>(
                    null,
                    (Class<T>) constructor.getDeclaringClass(),
                    null,
                    createdObject,
                    interpolate(customViolation.messageTemplate(), constraint, createdObject),
                    customViolation.messageTemplate(),
                    customViolation.path(),
                    constraint
                ));
            }
        }
        if (cascaded) {
            validateCascadedValue(
                null,
                constructor.getDeclaringClass(),
                createdObject,
                createdObject,
                context,
                violations,
                new ReflectionConstructorReturnValueExecutablePath(constructor)
            );
        }
        return Collections.unmodifiableSet(violations);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> Set<ConstraintViolation<T>> validateConstructorParametersReflectively(Constructor<? extends T> constructor,
                                                                                      Object[] parameterValues,
                                                                                      BeanValidationContext context) {
        Parameter[] parameters = constructor.getParameters();
        if (parameters.length != parameterValues.length) {
            throw new IllegalArgumentException("The constructor parameter array must have exactly " + parameters.length + " elements.");
        }
        warnOnce(constructor.getDeclaringClass().getName(), constructor.getName(), "validating constructor parameters without Micronaut executable metadata");
        ReflectionGroupConversions.validateConstructorParameterDeclarations(constructor);
        List<String> parameterNames = configuration.getParameterNameProvider().getParameterNames(constructor);
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        validateConstructorConstraintDeclarations(constructor, context);
        validateConstructorCrossParameterConstraintsReflectively(constructor, parameterValues, context, violations);
        for (int i = 0; i < parameters.length; i++) {
            List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(parameters[i]);
            List<ReflectionContainerElement> containerElements = containerElementsFor(parameters[i].getAnnotatedType());
            if (constraints.isEmpty() && containerElements.isEmpty() && !isCascaded(parameters[i])) {
                continue;
            }
            Object value = parameterValues[i];
            for (ReflectionConstraintDescriptor constraint : constraints) {
                if (!isGroupIncluded(constraint, context)) {
                    continue;
                }
                jakarta.validation.Path path = new ReflectionConstructorExecutablePath(constructor, parameterName(parameterNames, parameters[i], i), i);
                ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(clockProvider, null, constraint.getMessageTemplate(), path);
                boolean valid = validateConstraint(constraint, value, constructor.getParameterTypes()[i], validatorContext);
                if (!valid && !validatorContext.defaultViolationDisabled()) {
                    violations.add(new ReflectionConstraintViolation<>(
                        null,
                        (Class<T>) constructor.getDeclaringClass(),
                        null,
                        value,
                        interpolate(constraint.getMessageTemplate(), constraint, value),
                        constraint.getMessageTemplate(),
                        path,
                        constraint
                    ));
                }
                for (ReflectionConstraintValidatorContext.CustomViolation customViolation : validatorContext.customViolations()) {
                    violations.add(new ReflectionConstraintViolation<>(
                        null,
                        (Class<T>) constructor.getDeclaringClass(),
                        null,
                        value,
                        interpolate(customViolation.messageTemplate(), constraint, value),
                        customViolation.messageTemplate(),
                        customViolation.path(),
                        constraint
                    ));
                }
            }
            validateExecutableContainerElements(
                null,
                constructor.getDeclaringClass(),
                null,
                value,
                constructor.getParameterTypes()[i],
                containerElements,
                context,
                violations,
                constructorParameterContainerElementPath(constructor, parameterNames, parameters[i], i)
            );
            if (isCascaded(parameters[i]) && value != null) {
                int parameterIndex = i;
                String parameterName = parameterName(parameterNames, parameters[i], i);
                validateCascadedValue(
                    null,
                    constructor.getDeclaringClass(),
                    value,
                    value,
                    context,
                    violations,
                    new ReflectionConstructorExecutablePath(constructor, parameterName, parameterIndex)
                );
            }
        }
        return Collections.unmodifiableSet(violations);
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
        jakarta.validation.Path path = new ReflectionConstructorPath(constructor);
        for (ReflectionConstraintDescriptor constraint : constraints) {
            if (!isGroupIncluded(constraint, context) || !appliesTo(constraint, ConstraintTarget.PARAMETERS)) {
                continue;
            }
            ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(
                clockProvider,
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
            if (!valid && !validatorContext.defaultViolationDisabled()) {
                violations.add(new ReflectionConstraintViolation<>(
                    null,
                    (Class<T>) constructor.getDeclaringClass(),
                    null,
                    parameterValues,
                    interpolate(constraint.getMessageTemplate(), constraint, parameterValues),
                    constraint.getMessageTemplate(),
                    path,
                    constraint
                ));
            }
            for (ReflectionConstraintValidatorContext.CustomViolation customViolation : validatorContext.customViolations()) {
                violations.add(new ReflectionConstraintViolation<>(
                    null,
                    (Class<T>) constructor.getDeclaringClass(),
                    null,
                    parameterValues,
                    interpolate(customViolation.messageTemplate(), constraint, parameterValues),
                    customViolation.messageTemplate(),
                    customViolation.path(),
                    constraint
                ));
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> Set<ConstraintViolation<T>> validateParametersReflectively(T object,
                                                                           Method method,
                                                                           Object[] parameterValues,
                                                                           BeanValidationContext context) {
        Parameter[] parameters = method.getParameters();
        if (parameters.length != parameterValues.length) {
            throw new IllegalArgumentException("The method parameter array must have exactly " + parameters.length + " elements.");
        }
        warnOnce(method.getDeclaringClass().getName(), method.getName(), "validating executable parameters without Micronaut executable metadata");
        ReflectionMethodDeclarations.validateParameterDeclarations(method);
        ReflectionGroupConversions.validateMethodParameterDeclarations(method);
        List<String> parameterNames = configuration.getParameterNameProvider().getParameterNames(method);
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        validateCrossParameterConstraintsReflectively(object, method, parameterValues, context, parameterNames, violations);
        for (int i = 0; i < parameters.length; i++) {
            List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(parameters[i]);
            List<ReflectionContainerElement> containerElements = containerElementsFor(parameters[i].getAnnotatedType());
            if (constraints.isEmpty() && containerElements.isEmpty() && !isCascaded(parameters[i])) {
                continue;
            }
            Object value = parameterValues[i];
            for (ReflectionConstraintDescriptor constraint : constraints) {
                if (!isGroupIncluded(constraint, context)) {
                    continue;
                }
                jakarta.validation.Path path = new ReflectionExecutablePath(method, parameterName(parameterNames, parameters[i], i), i);
                ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(clockProvider, object, constraint.getMessageTemplate(), path);
                boolean valid = validateConstraint(constraint, value, method.getParameterTypes()[i], validatorContext, ConstraintTarget.IMPLICIT, true);
                if (!valid && !validatorContext.defaultViolationDisabled()) {
                    violations.add(new ReflectionConstraintViolation<>(
                        object,
                        (Class<T>) object.getClass(),
                        object,
                        value,
                        interpolate(constraint.getMessageTemplate(), constraint, value),
                        constraint.getMessageTemplate(),
                        path,
                        constraint
                    ));
                }
                for (ReflectionConstraintValidatorContext.CustomViolation customViolation : validatorContext.customViolations()) {
                    violations.add(new ReflectionConstraintViolation<>(
                        object,
                        (Class<T>) object.getClass(),
                        object,
                        value,
                        interpolate(customViolation.messageTemplate(), constraint, value),
                        customViolation.messageTemplate(),
                        customViolation.path(),
                        constraint
                    ));
                }
            }
            validateExecutableContainerElements(
                object,
                object.getClass(),
                object,
                value,
                method.getParameterTypes()[i],
                containerElements,
                context,
                violations,
                parameterContainerElementPath(method, parameterNames, parameters[i], i)
            );
            if (isCascaded(parameters[i]) && value != null) {
                int parameterIndex = i;
                String parameterName = parameterName(parameterNames, parameters[i], i);
                validateCascadedValue(
                    object,
                    object.getClass(),
                    value,
                    value,
                    context,
                    violations,
                    new ReflectionExecutablePath(method, parameterName, parameterIndex)
                );
            }
        }
        return Collections.unmodifiableSet(violations);
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void validateCrossParameterConstraintsReflectively(T object,
                                                                   Method method,
                                                                   Object[] parameterValues,
                                                                   BeanValidationContext context,
                                                                   List<String> parameterNames,
                                                                   Set<ConstraintViolation<T>> violations) {
        List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(method);
        if (constraints.isEmpty()) {
            return;
        }
        jakarta.validation.Path path = new ReflectionMethodExecutablePath(method);
        for (ReflectionConstraintDescriptor constraint : constraints) {
            if (!isGroupIncluded(constraint, context)) {
                continue;
            }
            validateExecutableConstraintDeclaration(constraint, method);
            if (!appliesTo(constraint, ConstraintTarget.PARAMETERS)) {
                continue;
            }
            ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(
                clockProvider,
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
            if (!valid && !validatorContext.defaultViolationDisabled()) {
                violations.add(new ReflectionConstraintViolation<>(
                    object,
                    (Class<T>) object.getClass(),
                    object,
                    parameterValues,
                    interpolate(constraint.getMessageTemplate(), constraint, parameterValues),
                    constraint.getMessageTemplate(),
                    path,
                    constraint
                ));
            }
            for (ReflectionConstraintValidatorContext.CustomViolation customViolation : validatorContext.customViolations()) {
                violations.add(new ReflectionConstraintViolation<>(
                    object,
                    (Class<T>) object.getClass(),
                    object,
                    parameterValues,
                    interpolate(customViolation.messageTemplate(), constraint, parameterValues),
                    customViolation.messageTemplate(),
                    customViolation.path(),
                    constraint
                ));
            }
        }
    }

    private <T> void validateProperty(T rootBean,
                                      Object leafBean,
                                      ReflectionProperty property,
                                      BeanValidationContext context,
                                      Set<ConstraintViolation<T>> violations,
                                      boolean supplementIntrospection,
                                      boolean validatePropertyConstraints) {
        Object value = property.read(leafBean);
        if (validatePropertyConstraints) {
            validatePropertyConstraints(rootBean, leafBean, property, value, context, violations, supplementIntrospection);
        }
        validateContainerElements(rootBean, leafBean, property, value, context, violations, supplementIntrospection);
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
            validateSingleConstraint(rootBean, rootBeanClass, leafBean, value, valueType, constraint, context, violations, propertyPath, resolveMostSpecific);
        }
    }

    private <T> void validatePropertyConstraints(@Nullable T rootBean,
                                                 @Nullable Object leafBean,
                                                 ReflectionProperty property,
                                                 @Nullable Object value,
                                                 BeanValidationContext context,
                                                 Set<ConstraintViolation<T>> violations,
                                                 boolean supplementIntrospection) {
        List<ReflectionConstraintDescriptor<?>> constraints = supplementIntrospection && rootBean != null
            ? supplementalPropertyConstraints(property.constraints, generatedPropertyConstraints(rootBean.getClass(), property.name), property.type)
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
                    new ReflectionPath(property.name)
                );
                continue;
            }
            unwrappingExtractor.valueExtractor().extractValues(value, new jakarta.validation.valueextraction.ValueExtractor.ValueReceiver() {

                @Override
                public void value(String nodeName, Object extractedValue) {
                    validateExtractedValue(nodeName, false, null, null, extractedValue);
                }

                @Override
                public void iterableValue(String nodeName, Object extractedValue) {
                    validateExtractedValue(nodeName, true, null, null, extractedValue);
                }

                @Override
                public void indexedValue(String nodeName, int index, Object extractedValue) {
                    validateExtractedValue(nodeName, true, null, index, extractedValue);
                }

                @Override
                public void keyedValue(String nodeName, Object key, Object extractedValue) {
                    validateExtractedValue(nodeName, true, key, null, extractedValue);
                }

                private void validateExtractedValue(String nodeName,
                                                    boolean iterable,
                                                    @Nullable Object key,
                                                    @Nullable Integer index,
                                                    @Nullable Object extractedValue) {
                    Integer typeArgumentIndex = resolveExtractedTypeArgumentIndex(
                        property.type,
                        unwrappingExtractor.containerType(),
                        unwrappingExtractor.typeArgumentIndex()
                    );
                    jakarta.validation.Path propertyPath = nodeName == null ? new ReflectionPath(property.name) : new ReflectionContainerElementPath(
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
                        propertyPath
                    );
                }
            });
        }
    }

    private ValueExtractorDefinition<Object> unwrappingExtractor(Class<?> propertyType,
                                                                 ReflectionConstraintDescriptor<?> constraint) {
        ValidateUnwrappedValue valueUnwrapping = constraint.getValueUnwrapping();
        if (valueUnwrapping == ValidateUnwrappedValue.SKIP) {
            return null;
        }
        List<ValueExtractorDefinition<Object>> valueExtractorDefinitions = valueExtractorRegistry.findValueExtractors((Class<Object>) propertyType);
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
        ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(clockProvider, rootBean, constraint.getMessageTemplate(), propertyPath);
        Boolean valid = validateConstraint(constraint, value, valueType, validatorContext, ConstraintTarget.IMPLICIT, resolveMostSpecific);
        if (valid == null) {
            if (validateComposingConstraints(rootBean, rootBeanClass, leafBean, propertyPath, value, valueType, constraint, context, violations)) {
                return;
            }
            throw new UnexpectedTypeException("Cannot find a constraint validator for constraint: " + constraint.getType().getName() + " and type: " + valueType);
        }
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
                violations.removeIf(violation -> !existingViolations.contains(violation));
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
        } else {
            validateConstraints(rootBean, rootBeanClass, leafBean, value, valueType, constraint.composingConstraints, context, violations, propertyPath);
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void validateContainerElements(T rootBean,
                                               Object leafBean,
                                               ReflectionProperty property,
                                               @Nullable Object containerValue,
                                               BeanValidationContext context,
                                               Set<ConstraintViolation<T>> violations,
                                               boolean supplementIntrospection) {
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
            List<ValueExtractorDefinition<Object>> valueExtractorDefinitions = valueExtractorRegistry.findValueExtractors(extractorLookupType);
            boolean foundExtractor = false;
            for (ValueExtractorDefinition<Object> valueExtractorDefinition : valueExtractorDefinitions) {
                if (!Objects.equals(valueExtractorDefinition.typeArgumentIndex(), containerElement.typeArgumentIndex)) {
                    continue;
                }
                foundExtractor = true;
                valueExtractorDefinition.valueExtractor().extractValues(containerValue, new jakarta.validation.valueextraction.ValueExtractor.ValueReceiver() {

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

                    private void validateContainerValue(String nodeName,
                                                        @Nullable Object key,
                                                        @Nullable Integer index,
                                                        boolean iterable,
                                                        @Nullable Object value) {
                        ReflectionContainerContext containerContext = new ReflectionContainerContext(
                            nodeName,
                            iterable,
                            key,
                            index,
                            property.type,
                            valueExtractorDefinition.typeArgumentIndex()
                        );
                        if (!containerElement.constraints.isEmpty()) {
                            validateConstraints(
                                rootBean,
                                rootBean.getClass(),
                                leafBean,
                                value,
                                containerElement.type,
                                containerElement.constraints,
                                context,
                                violations,
                                new ReflectionContainerElementPath(property.name, containerContext),
                                false
                            );
                        }
                        if (containerElement.cascaded && value != null) {
                            validateContainerCascadedValue(rootBean, value, property.name, containerContext, context, violations);
                        }
                    }
                });
            }
            if (!foundExtractor) {
                throw new ConstraintDeclarationException("Cannot validate container element constraints without a value extractor for type argument " + containerElement.typeArgumentIndex + " of " + property.type.getName());
            }
        }
    }

    private static boolean isSupplementalContainerElement(ReflectionContainerElement containerElement) {
        return containerElement.cascaded || containerElement.constraints.stream()
            .anyMatch(constraint -> !constraint.getConstraintValidatorClasses().isEmpty());
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
            List<ValueExtractorDefinition<Object>> valueExtractorDefinitions = valueExtractorRegistry.findValueExtractors((Class<Object>) containerType);
            for (ValueExtractorDefinition<Object> valueExtractorDefinition : valueExtractorDefinitions) {
                if (!Objects.equals(valueExtractorDefinition.typeArgumentIndex(), containerElement.typeArgumentIndex)) {
                    continue;
                }
                valueExtractorDefinition.valueExtractor().extractValues(containerValue, new jakarta.validation.valueextraction.ValueExtractor.ValueReceiver() {

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

                    private void validateContainerValue(String nodeName,
                                                        @Nullable Object key,
                                                        @Nullable Integer index,
                                                        boolean iterable,
                                                        @Nullable Object value) {
                        ReflectionContainerContext containerContext = new ReflectionContainerContext(
                            nodeName,
                            iterable,
                            key,
                            index,
                            containerType,
                            valueExtractorDefinition.typeArgumentIndex()
                        );
                        validateConstraints(
                            rootBean,
                            rootBeanClass,
                            leafBean,
                            value,
                            containerElement.type,
                            containerElement.constraints,
                            context,
                            violations,
                            pathFactory.apply(containerContext)
                        );
                    }
                });
            }
        }
    }

    private <T> void validateContainerCascadedValue(T rootBean,
                                                    Object value,
                                                    String propertyName,
                                                    ReflectionContainerContext containerContext,
                                                    BeanValidationContext context,
                                                    Set<ConstraintViolation<T>> violations) {
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(value.getClass());
        validateConstraints(
            rootBean,
            rootBean.getClass(),
            value,
            value,
            value.getClass(),
            metadata.constraints,
            context,
            violations,
            new ReflectionContainerElementPath(propertyName, containerContext)
        );
        Set<String> cascadedProperties = new LinkedHashSet<>();
        for (List<ReflectionProperty> properties : metadata.properties.values()) {
            for (ReflectionProperty property : properties) {
                Object propertyValue = property.read(value);
                validateConstraints(
                    rootBean,
                    rootBean.getClass(),
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
    }

    private <T> void validateCascadedValue(@Nullable T rootBean,
                                           @Nullable Class<?> rootBeanClass,
                                           @Nullable Object leafBean,
                                           @Nullable Object value,
                                           BeanValidationContext context,
                                           Set<ConstraintViolation<T>> violations,
                                           jakarta.validation.Path beanPath) {
        if (value == null) {
            return;
        }
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(value.getClass());
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
                Object propertyValue = property.read(value);
                jakarta.validation.Path propertyPath = new ReflectionAppendedPropertyPath(beanPath, property.name);
                validateConstraints(
                    rootBean,
                    rootBeanClass,
                    value,
                    propertyValue,
                    property.type,
                    property.constraints,
                    context,
                    violations,
                    propertyPath
                );
                if (propertyValue != null && property.isCascaded() && cascadedProperties.add(property.name)) {
                    validateCascadedValue(
                        rootBean,
                        rootBeanClass,
                        propertyValue,
                        propertyValue,
                        context,
                        violations,
                        propertyPath
                    );
                }
            }
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
            Class<? extends jakarta.validation.ConstraintValidator<?, ?>> validatorClass = ReflectionConstraintValidatorResolution.resolve(
                constraint.getType(),
                validatorClasses,
                valueType,
                constraintTarget
            );
            validatorClasses = validatorClass == null ? List.of() : List.of(validatorClass);
        }
        for (Object validatorClass : validatorClasses) {
            Class<? extends jakarta.validation.ConstraintValidator> validatorType = (Class<? extends jakarta.validation.ConstraintValidator>) validatorClass;
            jakarta.validation.ConstraintValidatorFactory constraintValidatorFactory = configuration.getConstraintValidatorFactory();
            jakarta.validation.ConstraintValidator validator = constraintValidatorFactory instanceof InternalConstraintValidatorFactory internalFactory
                ? internalFactory.getInstance(validatorType, valueType, constraintTarget)
                : constraintValidatorFactory.getInstance(validatorType);
            if (validator == null) {
                continue;
            }
            try {
                validator.initialize(constraint.getAnnotation());
                return validator.isValid(value, validatorContext);
            } finally {
                constraintValidatorFactory.releaseInstance(validator);
            }
        }
        Optional<ConstraintValidator<Annotation, Object>> validator = (Optional) configuration.getConstraintValidatorRegistry()
            .findConstraintValidator(constraint.getType(), ReflectionUtils.getWrapperType(valueType));
        if (validator.isPresent()) {
            return validator.get().isValid(value, constraint.annotationValue, validatorContext);
        }
        return null;
    }

    private String interpolate(String template, ReflectionConstraintDescriptor<?> descriptor, @Nullable Object value) {
        return messageInterpolator.interpolate(template, new InterpolationContext(descriptor, value));
    }

    private static boolean isGroupIncluded(ReflectionConstraintDescriptor<?> descriptor, BeanValidationContext context) {
        List<Class<?>> groups = context.groups();
        Set<Class<?>> descriptorGroups = descriptor.getGroups();
        if (groups.isEmpty()) {
            return descriptorGroups.contains(jakarta.validation.groups.Default.class);
        }
        return groups.stream().anyMatch(descriptorGroups::contains);
    }

    private static boolean appliesTo(ReflectionConstraintDescriptor<?> descriptor, ConstraintTarget target) {
        ConstraintTarget validationAppliesTo = descriptor.getValidationAppliesTo();
        return validationAppliesTo == ConstraintTarget.IMPLICIT || validationAppliesTo == target;
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
        if (StringUtils.isEmpty(value)) {
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

    private static List<ReflectionConstraintDescriptor<?>> constraintsFor(AnnotatedElement element, @Nullable Class<?> implicitGroup) {
        List<ReflectionConstraintDescriptor<?>> constraints = new ArrayList<>();
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType.isAnnotationPresent(Constraint.class)) {
                constraints.add(new ReflectionConstraintDescriptor<>(annotation, implicitGroup));
            } else {
                constraints.addAll(containedConstraints(annotation, implicitGroup));
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
            if (!constraints.isEmpty() || cascaded) {
                containerElements.add(new ReflectionContainerElement(
                    i,
                    getClassFromType(typeArgument.getType()),
                    constraints,
                    cascaded
                ));
            }
        }
        return List.copyOf(containerElements);
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
        try {
            Method valueMethod = container.annotationType().getDeclaredMethod("value");
            if (!valueMethod.getReturnType().isArray() || !Annotation.class.isAssignableFrom(valueMethod.getReturnType().getComponentType())) {
                return List.of();
            }
            valueMethod.setAccessible(true);
            Annotation[] annotations = (Annotation[]) valueMethod.invoke(container);
            return Arrays.stream(annotations)
                .filter(annotation -> annotation.annotationType().isAnnotationPresent(Constraint.class))
                .map(annotation -> new ReflectionConstraintDescriptor<>(annotation, implicitGroup))
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

    private record ReflectionProperty(
        String name,
        Class<?> type,
        AccessibleObject source,
        List<ReflectionConstraintDescriptor<?>> constraints,
        List<ReflectionContainerElement> containerElements
    ) {

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
            if (source.isAnnotationPresent(Valid.class)) {
                return true;
            }
            AnnotatedType annotatedType = source instanceof Field field
                ? field.getAnnotatedType()
                : ((Method) source).getAnnotatedReturnType();
            return annotatedType.isAnnotationPresent(Valid.class);
        }
    }

    private record ConstraintKey(
        Class<? extends Annotation> annotationType,
        Map<String, Object> attributes
    ) {

        static ConstraintKey of(ReflectionConstraintDescriptor<?> constraint) {
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
        int typeArgumentIndex,
        Class<?> type,
        List<ReflectionConstraintDescriptor<?>> constraints,
        boolean cascaded
    ) {
    }

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

        static ReflectionBeanMetadata of(Class<?> beanType) {
            Map<String, List<ReflectionProperty>> properties = new LinkedHashMap<>();
            for (Class<?> current = beanType; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(field, current);
                    List<ReflectionContainerElement> containerElements = containerElementsFor(field.getAnnotatedType());
                    if (!constraints.isEmpty() || !containerElements.isEmpty() || isCascaded(field)) {
                        addProperty(properties, new ReflectionProperty(field.getName(), field.getType(), field, constraints, containerElements));
                    }
                }
                for (Method method : current.getDeclaredMethods()) {
                    if (method.getParameterCount() != 0 || method.getReturnType() == Void.TYPE || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                        continue;
                    }
                    String propertyName = propertyName(method);
                    if (propertyName == null) {
                        continue;
                    }
                    List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(method, current);
                    List<ReflectionContainerElement> containerElements = containerElementsFor(method.getAnnotatedReturnType());
                    if (!constraints.isEmpty() || !containerElements.isEmpty() || isCascaded(method)) {
                        addProperty(properties, new ReflectionProperty(propertyName, method.getReturnType(), method, constraints, containerElements));
                    }
                }
            }
            collectInterfaceProperties(beanType, properties, new LinkedHashSet<>());
            List<ReflectionConstraintDescriptor<?>> constraints = new ArrayList<>();
            collectTypeConstraints(beanType, constraints, new LinkedHashSet<>());
            return new ReflectionBeanMetadata(beanType, constraints, properties);
        }

        private static void addProperty(Map<String, List<ReflectionProperty>> properties, ReflectionProperty property) {
            properties.computeIfAbsent(property.name, ignored -> new ArrayList<>()).add(property);
        }

        private static void collectInterfaceProperties(Class<?> type,
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
                    addProperty(properties, new ReflectionProperty(propertyName, method.getReturnType(), method, constraints, containerElements));
                }
            }
            for (Class<?> parent : interfaceType.getInterfaces()) {
                collectInterfaceProperties(parent, implicitGroup, properties, visited);
            }
        }

        private static void collectTypeConstraints(Class<?> type,
                                                   List<ReflectionConstraintDescriptor<?>> constraints,
                                                   Set<Class<?>> visited) {
            if (type == null || type == Object.class || !visited.add(type)) {
                return;
            }
            constraints.addAll(constraintsFor(type, type));
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
        public @Nullable PropertyDescriptor getConstraintsForProperty(String propertyName) {
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
        public @Nullable MethodDescriptor getConstraintsForMethod(String methodName, Class<?>... parameterTypes) {
            return null;
        }

        @Override
        public Set<MethodDescriptor> getConstrainedMethods(MethodType methodType, MethodType... methodTypes) {
            return Set.of();
        }

        @Override
        public @Nullable ConstructorDescriptor getConstraintsForConstructor(Class<?>... parameterTypes) {
            return null;
        }

        @Override
        public Set<ConstructorDescriptor> getConstrainedConstructors() {
            return Set.of();
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
    }

    private record ReflectionPropertyDescriptor(
        String propertyName,
        List<ReflectionProperty> properties
    ) implements PropertyDescriptor, ElementDescriptor.ConstraintFinder {

        @Override
        public String getPropertyName() {
            return propertyName;
        }

        @Override
        public boolean isCascaded() {
            return false;
        }

        @Override
        public Set<GroupConversionDescriptor> getGroupConversions() {
            return Set.of();
        }

        @Override
        public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
            return Set.of();
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
            return properties.stream()
                .flatMap(property -> property.constraints.stream())
                .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public ConstraintFinder findConstraints() {
            return this;
        }
    }

    private static final class ReflectionConstraintDescriptor<A extends Annotation> implements ConstraintDescriptor<A> {

        private final A annotation;
        private final Class<A> type;
        private final Set<Class<?>> groups;
        private final Set<Class<? extends Payload>> payload;
        private final List<Class<? extends jakarta.validation.ConstraintValidator<A, ?>>> validators;
        private final AnnotationValue<A> annotationValue;
        private final List<ReflectionConstraintDescriptor<?>> composingConstraints;
        private final boolean hasValidationAppliesTo;

        @SuppressWarnings({"unchecked", "rawtypes"})
        private ReflectionConstraintDescriptor(A annotation) {
            this(annotation, null);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private ReflectionConstraintDescriptor(A annotation, @Nullable Class<?> implicitGroup) {
            this.annotation = annotation;
            this.type = (Class<A>) annotation.annotationType();
            ReflectionConstraintDefinitions.validate(type);
            this.groups = groups(annotation, implicitGroup);
            this.payload = Set.of((Class<? extends Payload>[]) readMember(annotation, "payload", new Class<?>[0]));
            this.validators = List.of((Class[]) type.getAnnotation(Constraint.class).validatedBy());
            this.annotationValue = new AnnotationValue<>(type.getName(), annotationMembers(annotation, false), annotationMembers(annotation, true));
            this.composingConstraints = composingConstraints(annotation, groups, payload);
            this.hasValidationAppliesTo = hasMember(annotation.annotationType(), "validationAppliesTo");
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private ReflectionConstraintDescriptor(A annotation,
                                               Set<Class<?>> groups,
                                               Set<Class<? extends Payload>> payload,
                                               Map<CharSequence, Object> annotationMembers) {
            this.annotation = annotation;
            this.type = (Class<A>) annotation.annotationType();
            ReflectionConstraintDefinitions.validate(type);
            this.groups = groups;
            this.payload = payload;
            this.validators = List.of((Class[]) type.getAnnotation(Constraint.class).validatedBy());
            this.annotationValue = new AnnotationValue<>(type.getName(), annotationMembers, annotationMembers(annotation, true));
            this.composingConstraints = composingConstraints(annotation, groups, payload);
            this.hasValidationAppliesTo = hasMember(annotation.annotationType(), "validationAppliesTo");
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
        public ConstraintTarget getValidationAppliesTo() {
            return (ConstraintTarget) readMember(annotation, "validationAppliesTo", ConstraintTarget.IMPLICIT);
        }

        boolean hasValidationAppliesTo() {
            return hasValidationAppliesTo;
        }

        @Override
        public List<Class<? extends jakarta.validation.ConstraintValidator<A, ?>>> getConstraintValidatorClasses() {
            return validators;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return annotationMembers(annotation, false)
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
            Set<Class<?>> groups = new LinkedHashSet<>(List.of((Class<?>[]) readMember(annotation, "groups", new Class<?>[0])));
            if (implicitGroup != null && (groups.isEmpty() || groups.contains(jakarta.validation.groups.Default.class))) {
                groups.add(jakarta.validation.groups.Default.class);
                groups.add(implicitGroup);
            }
            return Set.copyOf(groups);
        }

        private static List<ReflectionConstraintDescriptor<?>> composingConstraints(
            Annotation parentAnnotation,
            Set<Class<?>> groups,
            Set<Class<? extends Payload>> payload) {
            List<ReflectionConstraintDescriptor<?>> constraints = new ArrayList<>();
            for (Annotation annotation : parentAnnotation.annotationType().getDeclaredAnnotations()) {
                if (annotation.annotationType().isAnnotationPresent(Constraint.class)) {
                    Map<CharSequence, Object> members = annotationMembers(annotation, false);
                    applyOverrides(parentAnnotation, annotation.annotationType(), members);
                    members.put("groups", groups.toArray(Class<?>[]::new));
                    members.put("payload", payload.toArray(Class<?>[]::new));
                    constraints.add(new ReflectionConstraintDescriptor(annotation, groups, payload, members));
                }
            }
            return List.copyOf(constraints);
        }

        private static void applyOverrides(
            Annotation parentAnnotation,
            Class<? extends Annotation> composingType,
            Map<CharSequence, Object> members) {
            Map<CharSequence, Object> parentMembers = annotationMembers(parentAnnotation, false);
            for (Method method : parentAnnotation.annotationType().getDeclaredMethods()) {
                Object value = parentMembers.get(method.getName());
                if (value == null) {
                    continue;
                }
                OverridesAttribute override = method.getAnnotation(OverridesAttribute.class);
                if (override != null) {
                    applyOverride(composingType, members, method, value, override);
                }
                OverridesAttribute.List overrides = method.getAnnotation(OverridesAttribute.List.class);
                if (overrides != null) {
                    for (OverridesAttribute listedOverride : overrides.value()) {
                        applyOverride(composingType, members, method, value, listedOverride);
                    }
                }
            }
        }

        private static void applyOverride(
            Class<? extends Annotation> composingType,
            Map<CharSequence, Object> members,
            Method method,
            Object value,
            OverridesAttribute override) {
            if (override.constraint() == composingType) {
                String name = override.name().isEmpty() ? method.getName() : override.name();
                members.put(name, value);
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
        ConstraintDescriptor<?> constraintDescriptor
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
            return null;
        }

        @Override
        public Object getExecutableReturnValue() {
            return null;
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

    private static final class ReflectionBeanNode implements Path.BeanNode {

        @Override
        public ElementKind getKind() {
            return ElementKind.BEAN;
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
        public String getName() {
            return null;
        }

        @Override
        public <T extends Path.Node> T as(Class<T> nodeType) {
            return nodeType.cast(this);
        }

        @Override
        public Class<?> getContainerClass() {
            return null;
        }

        @Override
        public Integer getTypeArgumentIndex() {
            return null;
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
            parent.iterator().forEachRemaining(nodes::add);
            nodes.add(new ReflectionNode(propertyName));
            return nodes.iterator();
        }

        @Override
        public String toString() {
            return parent + "." + propertyName;
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

    private static final class ReflectionReturnValueNode implements Path.ReturnValueNode {

        @Override
        public String getName() {
            return "<return value>";
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.RETURN_VALUE;
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
        public <T extends Path.Node> T as(Class<T> nodeType) {
            return nodeType.cast(this);
        }

        @Override
        public String toString() {
            return "<return value>";
        }
    }

    private record ReflectionConstructorNode(Constructor<?> constructor) implements Path.ConstructorNode {

        @Override
        public String getName() {
            return constructor.getDeclaringClass().getSimpleName();
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.CONSTRUCTOR;
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
        public <T extends Path.Node> T as(Class<T> nodeType) {
            return nodeType.cast(this);
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

    private record ReflectionMethodNode(Method method) implements Path.MethodNode {

        @Override
        public String getName() {
            return method.getName();
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.METHOD;
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
        public <T extends Path.Node> T as(Class<T> nodeType) {
            return nodeType.cast(this);
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

    private record ReflectionParameterNode(String name, int parameterIndex) implements Path.ParameterNode {

        @Override
        public ElementKind getKind() {
            return ElementKind.PARAMETER;
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
        public String getName() {
            return name;
        }

        @Override
        public <T extends Path.Node> T as(Class<T> nodeType) {
            return nodeType.cast(this);
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
