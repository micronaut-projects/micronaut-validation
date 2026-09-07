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
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.validation.validator.constraints.ConstraintValidatorTargetResolver;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.constraintvalidation.ValidationTarget;
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ValidateUnwrappedValue;
import jakarta.validation.valueextraction.Unwrapping;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            return retainedComposingConstraints(constraintType, retained, parentAnnotationValue, annotationMetadata);
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
        Class<? extends Annotation> constraintType,
        List<AnnotationValue<?>> retained,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        AnnotationMetadata annotationMetadata) {
        List<RetainedComposing> composing = new ArrayList<>();
        for (AnnotationValue<?> stereotype : retained) {
            if (isRetainedConstraint(stereotype)) {
                composing.add(new RetainedComposing(composingType(constraintType, stereotype.getAnnotationName()), stereotype));
            }
        }
        checkRetainedComposition(constraintType, parentAnnotationValue, composing);
        Set<DefaultConstraintDescriptor<Annotation>> composingConstraints = new LinkedHashSet<>();
        for (RetainedComposing constraint : composing) {
            composingConstraints.add(retainedComposingConstraint(constraint.type(), constraint.value(), parentAnnotationValue, annotationMetadata));
        }
        return Collections.unmodifiableSet(composingConstraints);
    }

    /**
     * The rules of a composition the retained tree can answer: a composed constraint and the constraints
     * composing it share a validation target, and a member overriding a member of a composing constraint has
     * the type of that member - the tree carries the value written with the type it was written with, next to
     * the default of the member it overrides. The rules only the declared form of the annotation type answers
     * are left to the reflection module, where it is present.
     */
    private static void checkRetainedComposition(Class<? extends Annotation> constraintType,
                                                 AnnotationValue<? extends Annotation> parentAnnotationValue,
                                                 List<RetainedComposing> composing) {
        if (composing.isEmpty()) {
            return;
        }
        Set<ValidationTarget> common = EnumSet.copyOf(ConstraintValidatorTargetResolver.constraintTargets(constraintType));
        for (RetainedComposing constraint : composing) {
            common.retainAll(ConstraintValidatorTargetResolver.constraintTargets(constraint.type()));
            if (common.isEmpty()) {
                throw new ConstraintDefinitionException("Composing constraints must share a validation target with the composed constraint: " + constraintType.getName());
            }
        }
        for (RetainedComposing constraint : composing) {
            Map<CharSequence, Object> defaultValues = constraint.value().getDefaultValues();
            if (defaultValues == null) {
                continue;
            }
            for (Map.Entry<CharSequence, Object> member : constraint.value().getValues().entrySet()) {
                String name = member.getKey().toString();
                Object defaultValue = defaultValues.get(name);
                if (!name.startsWith("$") && defaultValue != null && !sameKind(member.getValue(), defaultValue)) {
                    throw new ConstraintDefinitionException("The member of " + constraintType.getName() + " overriding "
                        + constraint.type().getName() + "." + name + " does not have the type of that member");
                }
            }
        }
        ReflectionSupport.get().checkComposition(constraintType, parentAnnotationValue);
    }

    /**
     * Whether a value written for a member is of the kind of the member's default: the metadata stores a
     * value with the type of the member it was written for, so a String written over an int member is a String
     * next to an Integer default, and an array over a single-valued member an array next to a value.
     */
    private static boolean sameKind(Object value, Object defaultValue) {
        Class<?> valueType = value.getClass();
        Class<?> defaultType = defaultValue.getClass();
        if (valueType.isArray() || defaultType.isArray()) {
            // an empty array says nothing of its component type: the metadata writes one for a member left empty
            return valueType.isArray() && defaultType.isArray()
                && (valueType.getComponentType() == defaultType.getComponentType() || isEmptyArray(value) || isEmptyArray(defaultValue));
        }
        return valueType == defaultType;
    }

    private static boolean isEmptyArray(Object array) {
        return array instanceof Object[] objects && objects.length == 0;
    }

    /**
     * A composing constraint the tree names, loaded through the loader of the composed constraint: the one the
     * application sees the composition through. A type the validator's own loader can also see, as in a test
     * archive, is another class to a caller comparing them.
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> composingType(Class<? extends Annotation> constraintType, String name) {
        return (Class<? extends Annotation>) ClassUtils
            .forName(name, constraintType.getClassLoader())
            .or(() -> ClassUtils.forName(name, DefaultConstraintDescriptor.class.getClassLoader()))
            .filter(Class::isAnnotation)
            .orElseThrow(() -> new ConstraintDeclarationException("Cannot load the composing constraint " + name));
    }

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
        Class<? extends Annotation> annotationType,
        AnnotationValue<?> composing,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        AnnotationMetadata annotationMetadata) {
        String name = composing.getAnnotationName();
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
     * The constraints a constraint composes where the processors retained no tree of them - the constraint of
     * a library, or of a type the processors never saw - read from the annotations of the constraint type by
     * the reflection module where it is present, and none otherwise.
     */
    @SuppressWarnings("unchecked")
    private static Set<DefaultConstraintDescriptor<Annotation>> reflectedComposingConstraints(
        Class<? extends Annotation> constraintType,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        AnnotationMetadata annotationMetadata) {
        Set<DefaultConstraintDescriptor<Annotation>> composingConstraints = new LinkedHashSet<>();
        for (ReflectionSupport.ComposingConstraint composing : ReflectionSupport.get().composingConstraints(constraintType, parentAnnotationValue)) {
            Class<Annotation> annotationType = (Class<Annotation>) composing.type();
            AnnotationValue<Annotation> annotationValue = composing.value();
            List<Class<? extends ConstraintValidator<Annotation, ?>>> validators = (List) List.of(annotationValue.classValues(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY));
            composingConstraints.add(validators.isEmpty()
                ? new DefaultConstraintDescriptor<>(annotationType, annotationValue, annotationMetadata)
                : new DefaultConstraintDescriptor<>(annotationType, annotationValue, annotationMetadata, validators, true));
        }
        return Collections.unmodifiableSet(composingConstraints);
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


    /**
     * A constraint the retained tree names as composing another, with its type loaded.
     *
     * @param type  The composing constraint type
     * @param value The occurrence in the tree, the overrides of the composed constraint applied
     */
    private record RetainedComposing(Class<? extends Annotation> type, AnnotationValue<?> value) {
    }
}
