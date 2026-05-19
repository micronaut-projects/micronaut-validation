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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.validation.validator.BeanValidationContext;
import io.micronaut.validation.validator.DefaultValidator;
import io.micronaut.validation.validator.ValidatorConfiguration;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
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
        BeanIntrospection<T> introspection = getBeanIntrospection(object);
        if (introspection != null) {
            Set<ConstraintViolation<T>> existing = super.validate(object, groups);
            Set<ConstraintViolation<T>> reflected = validateReflectively(object, BeanValidationContext.fromGroups(groups), true);
            return mergeViolations(existing, reflected);
        }
        return validateReflectively(object, BeanValidationContext.fromGroups(groups), false);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validate(T object, BeanValidationContext validationContext) {
        requireNonNull("object", object);
        BeanIntrospection<T> introspection = getBeanIntrospection(object);
        if (introspection != null) {
            BeanValidationContext context = validationContext == null ? BeanValidationContext.DEFAULT : validationContext;
            return mergeViolations(
                super.validate(object, context),
                validateReflectively(object, context, true)
            );
        }
        return validateReflectively(object, validationContext, false);
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

    private <T> Set<ConstraintViolation<T>> validateReflectively(T object,
                                                                 BeanValidationContext context,
                                                                 boolean supplementIntrospection) {
        ReflectionBeanMetadata metadata = ReflectionBeanMetadata.of(object.getClass());
        warnOnce(object.getClass().getName(), "class", supplementIntrospection
            ? "supplementing Micronaut bean introspection with reflection metadata"
            : "validating without Micronaut bean introspection");
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        for (List<Class<?>> groupPass : ReflectionGroupSequences.validationGroupPasses(object.getClass(), context)) {
            int violationCount = violations.size();
            BeanValidationContext groupContext = BeanValidationContext.fromGroups(groupPass.toArray(Class<?>[]::new));
            validateConstraints(object, object.getClass(), object, object, object.getClass(), metadata.constraints, groupContext, violations, new ReflectionPath(null));
            for (List<ReflectionProperty> properties : metadata.properties.values()) {
                for (ReflectionProperty property : properties) {
                    validateProperty(object, object, property, groupContext, violations, supplementIntrospection, true);
                }
            }
            if (violations.size() > violationCount) {
                break;
            }
        }
        return Collections.unmodifiableSet(violations);
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
        List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(method);
        if (constraints.isEmpty()) {
            return Collections.emptySet();
        }
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        for (ReflectionConstraintDescriptor constraint : constraints) {
            if (!isGroupIncluded(constraint, context)) {
                continue;
            }
            jakarta.validation.Path path = new ReflectionReturnValueExecutablePath(method);
            ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(clockProvider, object, constraint.getMessageTemplate(), path);
            boolean valid = validateConstraint(constraint, returnValue, method.getReturnType(), validatorContext);
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
        List<String> parameterNames = configuration.getParameterNameProvider().getParameterNames(constructor);
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        for (int i = 0; i < parameters.length; i++) {
            List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(parameters[i]);
            if (constraints.isEmpty()) {
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
        }
        return Collections.unmodifiableSet(violations);
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
        List<String> parameterNames = configuration.getParameterNameProvider().getParameterNames(method);
        Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
        for (int i = 0; i < parameters.length; i++) {
            List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(parameters[i]);
            if (constraints.isEmpty()) {
                continue;
            }
            Object value = parameterValues[i];
            for (ReflectionConstraintDescriptor constraint : constraints) {
                if (!isGroupIncluded(constraint, context)) {
                    continue;
                }
                jakarta.validation.Path path = new ReflectionExecutablePath(method, parameterName(parameterNames, parameters[i], i), i);
                ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(clockProvider, object, constraint.getMessageTemplate(), path);
                boolean valid = validateConstraint(constraint, value, method.getParameterTypes()[i], validatorContext);
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
        }
        return Collections.unmodifiableSet(violations);
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
            validatePropertyConstraints(rootBean, leafBean, property, value, context, violations);
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
        for (ReflectionConstraintDescriptor constraint : constraints) {
            if (!isGroupIncluded(constraint, context)) {
                continue;
            }
            validateSingleConstraint(rootBean, rootBeanClass, leafBean, value, valueType, constraint, context, violations, propertyPath);
        }
    }

    private <T> void validatePropertyConstraints(@Nullable T rootBean,
                                                 @Nullable Object leafBean,
                                                 ReflectionProperty property,
                                                 @Nullable Object value,
                                                 BeanValidationContext context,
                                                 Set<ConstraintViolation<T>> violations) {
        for (ReflectionConstraintDescriptor<?> constraint : property.constraints) {
            if (!isGroupIncluded(constraint, context)) {
                continue;
            }
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
        ReflectionConstraintValidatorContext validatorContext = new ReflectionConstraintValidatorContext(clockProvider, rootBean, constraint.getMessageTemplate(), propertyPath);
        Boolean valid = validateConstraint(constraint, value, valueType, validatorContext);
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
                                new ReflectionContainerElementPath(property.name, containerContext)
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private @Nullable Boolean validateConstraint(ReflectionConstraintDescriptor constraint,
                                       @Nullable Object value,
                                       Class<?> valueType,
                                       ReflectionConstraintValidatorContext validatorContext) {
        for (Object validatorClass : constraint.getConstraintValidatorClasses()) {
            Class<? extends jakarta.validation.ConstraintValidator> validatorType = (Class<? extends jakarta.validation.ConstraintValidator>) validatorClass;
            jakarta.validation.ConstraintValidator validator = configuration.getConstraintValidatorFactory().getInstance(validatorType);
            if (validator == null) {
                continue;
            }
            try {
                validator.initialize(constraint.getAnnotation());
                return validator.isValid(value, validatorContext);
            } finally {
                configuration.getConstraintValidatorFactory().releaseInstance(validator);
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
                    List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(field);
                    List<ReflectionContainerElement> containerElements = containerElementsFor(field.getAnnotatedType());
                    if (!constraints.isEmpty() || !containerElements.isEmpty()) {
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
                    List<ReflectionConstraintDescriptor<?>> constraints = constraintsFor(method);
                    List<ReflectionContainerElement> containerElements = containerElementsFor(method.getAnnotatedReturnType());
                    if (!constraints.isEmpty() || !containerElements.isEmpty()) {
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
                if (!constraints.isEmpty() || !containerElements.isEmpty()) {
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
            constraints.addAll(constraintsFor(type));
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
                .anyMatch(property -> !property.constraints.isEmpty() || !property.containerElements.isEmpty());
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

        @SuppressWarnings({"unchecked", "rawtypes"})
        private ReflectionConstraintDescriptor(A annotation) {
            this(annotation, null);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private ReflectionConstraintDescriptor(A annotation, @Nullable Class<?> implicitGroup) {
            this.annotation = annotation;
            this.type = (Class<A>) annotation.annotationType();
            this.groups = groups(annotation, implicitGroup);
            this.payload = Set.of((Class<? extends Payload>[]) readMember(annotation, "payload", new Class<?>[0]));
            this.validators = List.of((Class[]) type.getAnnotation(Constraint.class).validatedBy());
            this.annotationValue = new AnnotationValue<>(type.getName(), annotationMembers(annotation, false), annotationMembers(annotation, true));
            this.composingConstraints = composingConstraints(annotation, groups, payload);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private ReflectionConstraintDescriptor(A annotation,
                                               Set<Class<?>> groups,
                                               Set<Class<? extends Payload>> payload,
                                               Map<CharSequence, Object> annotationMembers) {
            this.annotation = annotation;
            this.type = (Class<A>) annotation.annotationType();
            this.groups = groups;
            this.payload = payload;
            this.validators = List.of((Class[]) type.getAnnotation(Constraint.class).validatedBy());
            this.annotationValue = new AnnotationValue<>(type.getName(), annotationMembers, annotationMembers(annotation, true));
            this.composingConstraints = composingConstraints(annotation, groups, payload);
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
