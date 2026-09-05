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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.AnnotationMetadataSupport;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import io.micronaut.validation.validator.constraints.ConstraintValidatorTargetResolver;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.OverridesAttribute;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ValidateUnwrappedValue;
import jakarta.validation.valueextraction.Unwrapping;
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.constraintvalidation.ValidationTarget;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.EnumSet;

/**
 * Default constraint descriptor implementation.
 *
 * @param <T> The constraint type
 * @author graemerocher
 * @since 1.2
 */
@Internal
class DefaultConstraintDescriptor<T extends Annotation> implements ConstraintDescriptor<T> {

    private static final String CONSTRAINT_ANNOTATION = jakarta.validation.Constraint.class.getName();

    private static final String ATTRIBUTE_MESSAGE = "message";
    private static final String ATTRIBUTE_GROUPS = "groups";
    private static final String ATTRIBUTE_PAYLOAD = "payload";
    private static final String ATTRIBUTE_VALIDATION_APPLIES_TO = "validationAppliesTo";

    @NonNull
    private final Class<T> type;
    @Nullable
    private final String message;
    @Nullable
    private final String defaultMessage;
    private final Set<Class<?>> groups;
    private final Set<Class<? extends Payload>> payload;
    private final List<Class<? extends ConstraintValidator<T, ?>>> validatedBy;
    private final boolean constraintValidatorClassesDefined;

    @Nullable
    private final ConstraintTarget validationAppliesTo;
    private final AnnotationValue<T> annotationValue;
    private final AnnotationMetadata annotationMetadata;
    private final Set<DefaultConstraintDescriptor<Annotation>> composingConstraints;
    private final boolean reportAsSingleViolation;

    DefaultConstraintDescriptor(@NonNull Class<T> constraintType,
                                @NonNull AnnotationValue<T> annotationValue,
                                @NonNull AnnotationMetadata annotationMetadata) {
        this(constraintType,
            annotationValue.stringValue(ATTRIBUTE_MESSAGE).orElse(null),
            annotationValue.getDefaultValues() == null ? null : (String) annotationValue.getDefaultValues().get(ATTRIBUTE_MESSAGE),
            Set.of(annotationValue.classValues(ATTRIBUTE_GROUPS)),
            (Set) Set.of(annotationValue.classValues(ATTRIBUTE_PAYLOAD)),
            (List) List.of(annotationValue.classValues(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY)),
            annotationValue.enumValue(ATTRIBUTE_VALIDATION_APPLIES_TO, ConstraintTarget.class).orElse(null),
            annotationValue,
            annotationMetadata);
    }

    DefaultConstraintDescriptor(@NonNull Class<T> constraintType,
                                @NonNull AnnotationValue<T> annotationValue,
                                @NonNull AnnotationMetadata annotationMetadata,
                                @NonNull List<Class<? extends ConstraintValidator<T, ?>>> validatedBy) {
        this(constraintType,
            annotationValue.stringValue(ATTRIBUTE_MESSAGE).orElse(null),
            annotationValue.getDefaultValues() == null ? null : (String) annotationValue.getDefaultValues().get(ATTRIBUTE_MESSAGE),
            Set.of(annotationValue.classValues(ATTRIBUTE_GROUPS)),
            (Set) Set.of(annotationValue.classValues(ATTRIBUTE_PAYLOAD)),
            validatedBy,
            true,
            annotationValue.enumValue(ATTRIBUTE_VALIDATION_APPLIES_TO, ConstraintTarget.class).orElse(null),
            annotationValue,
            annotationMetadata);
    }

    DefaultConstraintDescriptor(@NonNull Class<T> constraintType,
                                @NonNull AnnotationValue<T> annotationValue,
                                @NonNull AnnotationMetadata annotationMetadata,
                                @NonNull List<Class<? extends ConstraintValidator<T, ?>>> validatedBy,
                                boolean constraintValidatorClassesDefined) {
        this(constraintType,
            annotationValue.stringValue(ATTRIBUTE_MESSAGE).orElse(null),
            annotationValue.getDefaultValues() == null ? null : (String) annotationValue.getDefaultValues().get(ATTRIBUTE_MESSAGE),
            Set.of(annotationValue.classValues(ATTRIBUTE_GROUPS)),
            (Set) Set.of(annotationValue.classValues(ATTRIBUTE_PAYLOAD)),
            validatedBy,
            constraintValidatorClassesDefined,
            annotationValue.enumValue(ATTRIBUTE_VALIDATION_APPLIES_TO, ConstraintTarget.class).orElse(null),
            annotationValue,
            annotationMetadata);
    }

    DefaultConstraintDescriptor(@NonNull Class<T> type,
                                @Nullable String message,
                                @Nullable String defaultMessage,
                                @NonNull Set<Class<?>> groups,
                                @NonNull Set<Class<? extends Payload>> payload,
                                @NonNull List<Class<? extends ConstraintValidator<T, ?>>> validatedBy,
                                @Nullable ConstraintTarget validationAppliesTo,
                                @NonNull AnnotationValue<T> annotationValue,
                                @NonNull AnnotationMetadata annotationMetadata) {
        this(type, message, defaultMessage, groups, payload, validatedBy, !validatedBy.isEmpty(), validationAppliesTo, annotationValue, annotationMetadata);
    }

    @SuppressWarnings("java:S107")
    DefaultConstraintDescriptor(@NonNull Class<T> type,
                                @Nullable String message,
                                @Nullable String defaultMessage,
                                @NonNull Set<Class<?>> groups,
                                @NonNull Set<Class<? extends Payload>> payload,
                                @NonNull List<Class<? extends ConstraintValidator<T, ?>>> validatedBy,
                                boolean constraintValidatorClassesDefined,
                                @Nullable ConstraintTarget validationAppliesTo,
                                @NonNull AnnotationValue<T> annotationValue,
                                @NonNull AnnotationMetadata annotationMetadata) {
        this.type = type;
        this.message = message;
        this.defaultMessage = defaultMessage;
        this.groups = groups;
        this.payload = payload;
        this.validatedBy = validatedBy;
        this.constraintValidatorClassesDefined = constraintValidatorClassesDefined;
        this.validationAppliesTo = validationAppliesTo;
        this.annotationValue = annotationValue;
        this.annotationMetadata = annotationMetadata;
        this.composingConstraints = composingConstraints(type, annotationValue, annotationMetadata);
        this.reportAsSingleViolation = type.isAnnotationPresent(ReportAsSingleViolation.class);
    }

    public AnnotationValue<T> getAnnotationValue() {
        return annotationValue;
    }

    public AnnotationMetadata getAnnotationMetadata() {
        return annotationMetadata;
    }

    public Class<T> getType() {
        return type;
    }

    @Override
    public T getAnnotation() {
        return AnnotationMetadataSupport.buildAnnotation(type, annotationValue);
    }

    @Override
    public String getMessageTemplate() {
        if (message != null) {
            return message;
        }
        if (defaultMessage != null) {
            return defaultMessage;
        }
        return "{" + type.getName() + ".message}";
    }

    @Override
    public Set<Class<?>> getGroups() {
        if (groups.isEmpty()) {
            return Set.of(Default.class);
        }
        return groups;
    }

    @Override
    public Set<Class<? extends Payload>> getPayload() {
        return payload;
    }

    @Override
    public @Nullable ConstraintTarget getValidationAppliesTo() {
        return validationAppliesTo;
    }

    @Override
    public List<Class<? extends ConstraintValidator<T, ?>>> getConstraintValidatorClasses() {
        return validatedBy;
    }

    boolean hasDefinedConstraintValidatorClasses() {
        return constraintValidatorClassesDefined;
    }

    @Override
    public Map<String, Object> getAttributes() {
        final Map<?, ?> values = annotationValue.getValues();
        Map<String, Object> variables = CollectionUtils.newLinkedHashMap(values.size());
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            variables.put(entry.getKey().toString(), entry.getValue());
        }
        if (annotationValue.getDefaultValues() != null) {
            final Map<CharSequence, Object> defaultValues = annotationValue.getDefaultValues();
            for (Map.Entry<CharSequence, Object> entry : defaultValues.entrySet()) {
                final String n = entry.getKey().toString();
                if (!variables.containsKey(n)) {
                    final Object v = entry.getValue();
                    if (v != null) {
                        variables.put(n, v);
                    }
                }
            }
        }
        return variables;
    }

    @Override
    public Set<ConstraintDescriptor<?>> getComposingConstraints() {
        return Collections.unmodifiableSet((Set) composingConstraints);
    }

    @Override
    public boolean isReportAsSingleViolation() {
        return reportAsSingleViolation;
    }

    Set<DefaultConstraintDescriptor<Annotation>> getComposingConstraintDescriptors() {
        return composingConstraints;
    }

    boolean hasComposingConstraints() {
        return !composingConstraints.isEmpty();
    }

    @Override
    public ValidateUnwrappedValue getValueUnwrapping() {
        boolean unwrap = payload.contains(Unwrapping.Unwrap.class);
        boolean skip = payload.contains(Unwrapping.Skip.class);
        if (unwrap && skip) {
            throw new ConstraintDeclarationException("Payload declared with both " + Unwrapping.Unwrap.class.getName() + " and " + Unwrapping.Skip.class);
        }
        if (unwrap) {
            return ValidateUnwrappedValue.UNWRAP;
        }
        if (skip) {
            return ValidateUnwrappedValue.SKIP;
        }
        return ValidateUnwrappedValue.DEFAULT;
    }

    @Override
    public <U> U unwrap(Class<U> type) {
        throw new UnsupportedOperationException("Unwrapping unsupported");
    }

    private static Set<DefaultConstraintDescriptor<Annotation>> composingConstraints(
        Class<? extends Annotation> constraintType,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        AnnotationMetadata annotationMetadata) {
        List<AnnotationValue<?>> retained = parentAnnotationValue.getStereotypes();
        // the tree is what the processor built only when the constraint contract is in it: every constraint it
        // compiled retains the contract that marks it, so anything else - no tree, or one another caller put
        // together - is a constraint the processors never saw
        if (retained != null && containsConstraintContract(retained)) {
            return retainedComposingConstraints(retained, parentAnnotationValue, annotationMetadata);
        }
        return reflectedComposingConstraints(constraintType, parentAnnotationValue, annotationMetadata);
    }

    /**
     * The constraints a constraint composes, read off the retained tree the processor builds.
     *
     * <p>{@code jakarta.validation.Constraint} is marked {@link io.micronaut.core.annotation.Retainable}, so a
     * constraint the processor compiled keeps every constraint it composes as an occurrence of its own, with the
     * member overrides {@code @OverridesAttribute} declares already applied - the processor maps them onto
     * {@code @AliasFor}. Reading it describes a composed constraint without loading the annotation type back and
     * reading its members reflectively, which is the path every constraint of a compiled application takes.</p>
     */
    private static Set<DefaultConstraintDescriptor<Annotation>> retainedComposingConstraints(
        List<AnnotationValue<?>> retained,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        AnnotationMetadata annotationMetadata) {
        Set<DefaultConstraintDescriptor<Annotation>> composingConstraints = new LinkedHashSet<>();
        for (AnnotationValue<?> stereotype : retained) {
            if (!isRetainedConstraint(stereotype)) {
                continue;
            }
            composingConstraints.add(retainedComposingConstraint(stereotype, parentAnnotationValue, annotationMetadata));
        }
        return Collections.unmodifiableSet(composingConstraints);
    }

    /**
     * Whether the constraint contract is among a set of retained stereotypes.
     */
    private static boolean containsConstraintContract(List<AnnotationValue<?>> retained) {
        for (AnnotationValue<?> stereotype : retained) {
            if (CONSTRAINT_ANNOTATION.equals(stereotype.getAnnotationName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a retained occurrence is a constraint: the constraint contract is among its own stereotypes. The
     * contract itself keeps no subtree, so it is not taken for one of the constraints it marks.
     */
    private static boolean isRetainedConstraint(AnnotationValue<?> annotation) {
        List<AnnotationValue<?>> stereotypes = annotation.getStereotypes();
        if (stereotypes == null) {
            return false;
        }
        for (AnnotationValue<?> stereotype : stereotypes) {
            if (CONSTRAINT_ANNOTATION.equals(stereotype.getAnnotationName())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DefaultConstraintDescriptor<Annotation> retainedComposingConstraint(
        AnnotationValue<?> composing,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        AnnotationMetadata annotationMetadata) {
        String name = composing.getAnnotationName();
        Class<? extends Annotation> annotationType = (Class<? extends Annotation>) ClassUtils
            .forName(name, DefaultConstraintDescriptor.class.getClassLoader())
            .filter(Class::isAnnotation)
            .orElseThrow(() -> new ConstraintDeclarationException("Cannot load the composing constraint " + name));
        // the values the tree carries are the ones the composing annotation sets, the overrides of the composed
        // one already applied; the reserved member holding the subtree is not one of them
        Map<CharSequence, Object> values = new LinkedHashMap<>(composing.getValues());
        values.remove(AnnotationUtil.STEREOTYPES_MEMBER);
        Map<CharSequence, Object> defaultValues = composing.getDefaultValues() == null ? Map.of() : composing.getDefaultValues();
        // the target of the composed constraint applies to the composing ones declaring one
        ConstraintTarget validationAppliesTo = parentAnnotationValue.enumValue(ATTRIBUTE_VALIDATION_APPLIES_TO, ConstraintTarget.class).orElse(ConstraintTarget.IMPLICIT);
        if (validationAppliesTo != ConstraintTarget.IMPLICIT
            && (composing.contains(ATTRIBUTE_VALIDATION_APPLIES_TO) || defaultValues.containsKey(ATTRIBUTE_VALIDATION_APPLIES_TO))) {
            values.put(ATTRIBUTE_VALIDATION_APPLIES_TO, validationAppliesTo);
        }
        values.put(ATTRIBUTE_GROUPS, parentAnnotationValue.classValues(ATTRIBUTE_GROUPS));
        values.put(ATTRIBUTE_PAYLOAD, parentAnnotationValue.classValues(ATTRIBUTE_PAYLOAD));
        AnnotationValue<Annotation> annotationValue = (AnnotationValue<Annotation>) ConstraintContainers.withValidators(
            new AnnotationValue<>(name, values, defaultValues),
            annotationType
        );
        List<Class<? extends ConstraintValidator<Annotation, ?>>> validators = (List) List.of(annotationValue.classValues(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY));
        return validators.isEmpty()
            ? new DefaultConstraintDescriptor<>((Class<Annotation>) annotationType, annotationValue, annotationMetadata)
            : new DefaultConstraintDescriptor<>((Class<Annotation>) annotationType, annotationValue, annotationMetadata, validators, true);
    }

    /**
     * The constraints a constraint composes, read off the annotation type. A constraint the processors never saw
     * carries no retained tree - the type of a library, and every type the Jakarta Validation TCK declares - and
     * reading the class back is the only way to describe what it composes.
     */
    private static Set<DefaultConstraintDescriptor<Annotation>> reflectedComposingConstraints(
        Class<? extends Annotation> constraintType,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        AnnotationMetadata annotationMetadata) {
        List<ComposingAnnotation> composingAnnotations = composingAnnotations(constraintType);
        checkCompositionTargets(constraintType, composingAnnotations);
        Set<DefaultConstraintDescriptor<Annotation>> composingConstraints = new LinkedHashSet<>();
        for (ComposingAnnotation annotation : composingAnnotations) {
            composingConstraints.add(composingConstraint(annotation, constraintType, parentAnnotationValue, annotationMetadata, composingAnnotations));
        }
        return Collections.unmodifiableSet(composingConstraints);
    }

    /**
     * A composed constraint and the constraints composing it share a validation target: generic, cross-parameter
     * or both.
     */
    private static void checkCompositionTargets(Class<? extends Annotation> parentType, List<ComposingAnnotation> composingAnnotations) {
        if (composingAnnotations.isEmpty()) {
            return;
        }
        Set<ValidationTarget> common = EnumSet.copyOf(validationTargets(parentType));
        for (ComposingAnnotation composingAnnotation : composingAnnotations) {
            common.retainAll(validationTargets(composingAnnotation.annotation().annotationType()));
            if (common.isEmpty()) {
                throw new ConstraintDefinitionException("Composing constraints must share a validation target with the composed constraint: " + parentType.getName());
            }
        }
    }

    private static Set<ValidationTarget> validationTargets(Class<? extends Annotation> annotationType) {
        jakarta.validation.Constraint constraint = annotationType.getAnnotation(jakarta.validation.Constraint.class);
        if (constraint == null || constraint.validatedBy().length == 0) {
            return EnumSet.of(ValidationTarget.ANNOTATED_ELEMENT, ValidationTarget.PARAMETERS);
        }
        Set<ValidationTarget> targets = EnumSet.noneOf(ValidationTarget.class);
        for (Class<?> validator : constraint.validatedBy()) {
            Set<ValidationTarget> supported = ConstraintValidatorTargetResolver.validationTargets(validator);
            if (supported.isEmpty()) {
                // a validator declaring no target validates the annotated element
                targets.add(ValidationTarget.ANNOTATED_ELEMENT);
            } else {
                targets.addAll(supported);
            }
        }
        return targets;
    }

    private static DefaultConstraintDescriptor<Annotation> composingConstraint(
        ComposingAnnotation composingAnnotation,
        Class<? extends Annotation> parentType,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        AnnotationMetadata annotationMetadata,
        List<ComposingAnnotation> composingAnnotations) {
        Annotation annotation = composingAnnotation.annotation();
        Class<? extends Annotation> annotationType = annotation.annotationType();
        Map<CharSequence, Object> values = annotationValues(annotation);
        applyOverrides(annotationType, composingAnnotation.constraintIndex(), parentType, parentAnnotationValue, values, composingAnnotations);
        // the target of the composed constraint applies to the composing ones declaring one
        ConstraintTarget validationAppliesTo = parentAnnotationValue.enumValue(ATTRIBUTE_VALIDATION_APPLIES_TO, ConstraintTarget.class).orElse(ConstraintTarget.IMPLICIT);
        if (validationAppliesTo != ConstraintTarget.IMPLICIT && hasMember(annotationType, ATTRIBUTE_VALIDATION_APPLIES_TO)) {
            values.put(ATTRIBUTE_VALIDATION_APPLIES_TO, validationAppliesTo);
        }
        values.put(ATTRIBUTE_GROUPS, parentAnnotationValue.classValues(ATTRIBUTE_GROUPS));
        values.put(ATTRIBUTE_PAYLOAD, parentAnnotationValue.classValues(ATTRIBUTE_PAYLOAD));
        AnnotationValue<Annotation> annotationValue = (AnnotationValue<Annotation>) ConstraintContainers.withValidators(
            new AnnotationValue<>(annotationType.getName(), values, defaultValues(annotationType)),
            annotationType
        );
        List<Class<? extends ConstraintValidator<Annotation, ?>>> validators = (List) List.of(annotationValue.classValues(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY));
        return validators.isEmpty()
            ? new DefaultConstraintDescriptor<>((Class<Annotation>) annotationType, annotationValue, annotationMetadata)
            : new DefaultConstraintDescriptor<>((Class<Annotation>) annotationType, annotationValue, annotationMetadata, validators, true);
    }

    private static void applyOverrides(
        Class<? extends Annotation> composingType,
        int composingConstraintIndex,
        Class<? extends Annotation> parentType,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        Map<CharSequence, Object> values,
        List<ComposingAnnotation> composingAnnotations) {
        Map<String, Object> parentAttributes = attributes(parentAnnotationValue);
        for (Method method : parentType.getDeclaredMethods()) {
            Object value = parentAttributes.get(method.getName());
            if (value == null) {
                continue;
            }
            OverridesAttribute override = method.getAnnotation(OverridesAttribute.class);
            if (override != null) {
                applyOverride(composingType, composingConstraintIndex, values, method, value, override, composingAnnotations);
            }
            OverridesAttribute.List overrides = method.getAnnotation(OverridesAttribute.List.class);
            if (overrides != null) {
                for (OverridesAttribute listedOverride : overrides.value()) {
                    applyOverride(composingType, composingConstraintIndex, values, method, value, listedOverride, composingAnnotations);
                }
            }
        }
    }

    private static void applyOverride(
        Class<? extends Annotation> composingType,
        int composingConstraintIndex,
        Map<CharSequence, Object> values,
        Method method,
        Object value,
        OverridesAttribute override,
        List<ComposingAnnotation> composingAnnotations) {
        if (override.constraint() != composingType) {
            return;
        }
        long occurrences = composingAnnotations.stream().filter(annotation -> annotation.annotation().annotationType() == composingType).count();
        if (override.constraintIndex() >= occurrences) {
            throw new ConstraintDefinitionException("Invalid constraintIndex " + override.constraintIndex() + " overriding " + composingType.getName() + " in " + method.getDeclaringClass().getName());
        }
        if (override.constraintIndex() == -1 || override.constraintIndex() == composingConstraintIndex) {
            String name = override.name().isEmpty() ? method.getName() : override.name();
            checkOverride(method, value, composingType, name);
            values.put(name, value);
        }
    }

    /**
     * An overriding member has the type of the member it overrides.
     */
    private static void checkOverride(Method method, Object value, Class<? extends Annotation> composingType, String memberName) {
        Method member;
        try {
            member = composingType.getDeclaredMethod(memberName);
        } catch (NoSuchMethodException e) {
            throw new ConstraintDefinitionException("Cannot override the missing member " + composingType.getName() + "." + memberName + " from " + method.getDeclaringClass().getName(), e);
        }
        if (!isAssignableToMember(value, member.getReturnType())) {
            throw new ConstraintDefinitionException("The member " + method.getDeclaringClass().getName() + "." + method.getName() + " does not have the type of " + composingType.getName() + "." + memberName);
        }
    }

    /**
     * Whether a value, as the metadata stores it, fits a member type: a class is stored as its name, an enum
     * as its constant name, a primitive as its wrapper.
     */
    private static boolean isAssignableToMember(Object value, Class<?> memberType) {
        if (memberType.isArray()) {
            Class<?> valueType = value.getClass();
            if (valueType.isArray()) {
                return isAssignableToMember(java.lang.reflect.Array.getLength(value) == 0 ? null : java.lang.reflect.Array.get(value, 0), memberType.getComponentType());
            }
            return isAssignableToMember(value, memberType.getComponentType());
        }
        if (value == null) {
            return true;
        }
        if (memberType == Class.class) {
            return value instanceof Class || value instanceof AnnotationClassValue;
        }
        if (memberType.isEnum()) {
            return memberType.isInstance(value) || value instanceof String;
        }
        if (memberType.isAnnotation()) {
            return memberType.isInstance(value) || value instanceof AnnotationValue;
        }
        if (memberType.isPrimitive()) {
            return ReflectionUtils.getWrapperType(memberType) == value.getClass();
        }
        return memberType.isAssignableFrom(value.getClass());
    }

    private static boolean hasMember(Class<? extends Annotation> annotationType, String memberName) {
        for (Method method : annotationType.getDeclaredMethods()) {
            if (method.getName().equals(memberName) && method.getParameterCount() == 0) {
                return true;
            }
        }
        return false;
    }

    private static List<ComposingAnnotation> composingAnnotations(Class<? extends Annotation> constraintType) {
        List<ComposingAnnotation> composingAnnotations = new ArrayList<>();
        Map<Class<? extends Annotation>, Integer> constraintIndexes = new LinkedHashMap<>();
        Set<Class<? extends Annotation>> direct = new LinkedHashSet<>();
        Set<Class<? extends Annotation>> contained = new LinkedHashSet<>();
        for (Annotation annotation : constraintType.getDeclaredAnnotations()) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType.isAnnotationPresent(jakarta.validation.Constraint.class)) {
                if (contained.contains(annotationType)) {
                    throw new ConstraintDeclarationException("A constraint composes " + annotationType.getName() + " both directly and in a container: " + constraintType.getName());
                }
                direct.add(annotationType);
                int constraintIndex = constraintIndexes.merge(annotationType, 0, (previous, ignored) -> previous + 1);
                composingAnnotations.add(new ComposingAnnotation(annotation, constraintIndex));
                continue;
            }
            for (Annotation repeatedAnnotation : repeatedConstraintAnnotations(annotation)) {
                Class<? extends Annotation> repeatedAnnotationType = repeatedAnnotation.annotationType();
                if (direct.contains(repeatedAnnotationType)) {
                    throw new ConstraintDeclarationException("A constraint composes " + repeatedAnnotationType.getName() + " both directly and in a container: " + constraintType.getName());
                }
                contained.add(repeatedAnnotationType);
                int constraintIndex = constraintIndexes.merge(repeatedAnnotationType, 0, (previous, ignored) -> previous + 1);
                composingAnnotations.add(new ComposingAnnotation(repeatedAnnotation, constraintIndex));
            }
        }
        return composingAnnotations;
    }

    @SuppressWarnings("java:S3011")
    private static List<Annotation> repeatedConstraintAnnotations(Annotation annotation) {
        try {
            Method valueMethod = annotation.annotationType().getDeclaredMethod("value");
            if (valueMethod.getReturnType().isArray() && Annotation.class.isAssignableFrom(valueMethod.getReturnType().getComponentType())) {
                valueMethod.setAccessible(true);
                Annotation[] annotations = (Annotation[]) valueMethod.invoke(annotation);
                List<Annotation> constraints = new ArrayList<>(annotations.length);
                for (Annotation repeatedAnnotation : annotations) {
                    if (repeatedAnnotation.annotationType().isAnnotationPresent(jakarta.validation.Constraint.class)) {
                        constraints.add(repeatedAnnotation);
                    }
                }
                return constraints;
            }
        } catch (NoSuchMethodException e) {
            return List.of();
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new ConstraintDeclarationException("Cannot read composing constraint container " + annotation.annotationType().getName(), e);
        }
        return List.of();
    }

    private static Map<String, Object> attributes(AnnotationValue<? extends Annotation> annotationValue) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        annotationValue.getValues().forEach((key, value) -> attributes.put(key.toString(), value));
        Map<CharSequence, Object> defaultValues = annotationValue.getDefaultValues();
        if (defaultValues != null) {
            defaultValues.forEach((key, value) -> attributes.putIfAbsent(key.toString(), value));
        }
        return attributes;
    }

    @SuppressWarnings("java:S3011")
    private static Map<CharSequence, Object> annotationValues(Annotation annotation) {
        Map<CharSequence, Object> values = new LinkedHashMap<>();
        for (Method method : annotation.annotationType().getDeclaredMethods()) {
            try {
                method.setAccessible(true);
                Object value = method.invoke(annotation);
                if (value != null && !Objects.deepEquals(value, method.getDefaultValue())) {
                    values.put(method.getName(), value);
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ConstraintDeclarationException("Cannot read composing constraint " + annotation.annotationType().getName(), e);
            }
        }
        return values;
    }

    private static Map<CharSequence, Object> defaultValues(Class<? extends Annotation> annotationType) {
        Map<CharSequence, Object> defaultValues = new LinkedHashMap<>();
        for (Method method : annotationType.getDeclaredMethods()) {
            Object defaultValue = method.getDefaultValue();
            if (defaultValue != null) {
                defaultValues.put(method.getName(), defaultValue);
            }
        }
        return defaultValues;
    }

    private record ComposingAnnotation(
        Annotation annotation,
        int constraintIndex
    ) {
    }
}
