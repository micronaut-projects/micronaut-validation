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

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.validation.annotation.URL;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import io.micronaut.validation.validator.constraints.ConstraintValidatorTargetResolver;
import io.micronaut.validation.validator.metadata.ValidationEnumValues;
import io.micronaut.validation.validator.metadata.ValidationMetadataSupport;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.Payload;
import jakarta.validation.ValidationException;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraintvalidation.ValidationTarget;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ValidateUnwrappedValue;
import jakarta.validation.valueextraction.Unwrapping;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Default constraint descriptor implementation.
 *
 * @param <T> The constraint type
 * @author graemerocher
 * @since 1.2
 */
@Internal
final class DefaultConstraintDescriptor<T extends Annotation> implements ConstraintDescriptor<T>, MessageAttributes.Source {

    private static final String CONSTRAINT_ANNOTATION = Constraint.class.getName();

    private static final String ATTRIBUTE_MESSAGE = "message";
    private static final String ATTRIBUTE_GROUPS = "groups";
    private static final String ATTRIBUTE_PAYLOAD = "payload";
    private static final String ATTRIBUTE_VALIDATION_APPLIES_TO = "validationAppliesTo";

    @NonNull
    private final Class<T> type;
    private final ReflectionSupport reflectionSupport;
    @Nullable
    private final String message;
    @Nullable
    private final String defaultMessage;
    private final Set<Class<?>> groups;
    private final Set<Class<? extends Payload>> payload;
    private final List<Class<? extends ConstraintValidator<T, ?>>> validatedBy;
    private final boolean constraintValidatorClassesDefined;
    private final boolean builtInValidators;
    // the validator the last validation of this constraint resolved, with what it was resolved for: the
    // validator owning the instances, the validated type and the target
    private volatile @Nullable ResolvedValidator resolvedValidator;

    @Nullable
    private final ConstraintTarget validationAppliesTo;
    private final AnnotationValue<T> annotationValue;
    private final AnnotationMetadata annotationMetadata;
    private final Set<DefaultConstraintDescriptor<Annotation>> composingConstraints;
    private final boolean reportAsSingleViolation;

    /**
     * @param reflectionSupport The access provider captured by the validator factory
     * @param type The constraint type
     * @param annotationValue The occurrence of the constraint
     * @param annotationMetadata The metadata of the constrained element
     * @param validatedBy The validators of the constraint
     * @param constraintValidatorClassesDefined Whether the validators are defined, by the constraint or a configuration
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private DefaultConstraintDescriptor(ReflectionSupport reflectionSupport,
                                        @NonNull Class<T> type,
                                        @NonNull AnnotationValue<T> annotationValue,
                                        @NonNull AnnotationMetadata annotationMetadata,
                                        @NonNull List<Class<? extends ConstraintValidator<T, ?>>> validatedBy,
                                        boolean constraintValidatorClassesDefined) {
        this.type = type;
        this.reflectionSupport = reflectionSupport;
        this.message = annotationValue.stringValue(ATTRIBUTE_MESSAGE).orElse(null);
        this.defaultMessage = annotationValue.getDefaultValues() == null ? null : (String) annotationValue.getDefaultValues().get(ATTRIBUTE_MESSAGE);
        this.groups = Set.copyOf(Arrays.asList(annotationValue.classValues(ATTRIBUTE_GROUPS)));
        this.payload = (Set) Set.copyOf(Arrays.asList(annotationValue.classValues(ATTRIBUTE_PAYLOAD)));
        this.builtInValidators = validatedBy.contains(ConstraintValidatorOverrides.BuiltIn.class);
        this.validatedBy = builtInValidators
            ? validatedBy.stream().filter(validator -> (Object) validator != ConstraintValidatorOverrides.BuiltIn.class).toList()
            : validatedBy;
        this.constraintValidatorClassesDefined = constraintValidatorClassesDefined;
        this.validationAppliesTo = ValidationEnumValues.target(annotationValue);
        this.annotationValue = annotationValue;
        this.annotationMetadata = annotationMetadata;
        annotationValue.stringValue(ValidationAnnotationUtil.COMPOSITION_ERROR).ifPresent(error -> {
            throw new ConstraintDeclarationException(error);
        });
        annotationValue.stringValue(ValidationAnnotationUtil.COMPOSITION_DEFINITION_ERROR).ifPresent(error -> {
            throw new ConstraintDefinitionException(error);
        });
        this.composingConstraints = composingConstraints(type, annotationValue, annotationMetadata);
        // a stereotype where the metadata retains it, read from the type only for a constraint never compiled
        this.reportAsSingleViolation = isReportedAsSingleViolation(annotationValue, type);
    }

    /**
     * The descriptor of an occurrence of a constraint.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     * @param type The constraint type
     * @param annotationValue The occurrence of the constraint
     * @param annotationMetadata The metadata of the constrained element
     * @param validatorOverrides The validators a configuration defines for the constraint, {@code null} for the ones
     * the occurrence records
     * @param <T> The constraint type
     * @return The descriptor
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T extends Annotation> DefaultConstraintDescriptor<T> of(ReflectionSupport reflectionSupport,
                                                                   Class<T> type,
                                                                   AnnotationValue<T> annotationValue,
                                                                   AnnotationMetadata annotationMetadata,
                                                                   @Nullable List<Class<? extends ConstraintValidator<T, ?>>> validatorOverrides) {
        if (validatorOverrides != null) {
            return new DefaultConstraintDescriptor<>(reflectionSupport, type, annotationValue, annotationMetadata, validatorOverrides, true);
        }
        List<Class<? extends ConstraintValidator<T, ?>>> validatedBy =
            (List) List.of(annotationValue.classValues(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY));
        return new DefaultConstraintDescriptor<>(reflectionSupport, type, annotationValue, annotationMetadata, validatedBy, !validatedBy.isEmpty());
    }

    /**
     * Whether the constraint reports its composition as one violation: the marker is a stereotype
     * of the constraint itself, which the occurrence carries where the metadata retains it, and is
     * read from the annotation type only where it does not.
     */
    private boolean isReportedAsSingleViolation(AnnotationValue<?> annotationValue, Class<?> type) {
        var retained = annotationValue.booleanValue(ValidationAnnotationUtil.REPORT_AS_SINGLE_VIOLATION);
        if (retained.isPresent()) {
            return retained.get();
        }
        List<AnnotationValue<?>> stereotypes = annotationValue.getStereotypes();
        if (stereotypes != null) {
            for (AnnotationValue<?> stereotype : stereotypes) {
                if (stereotype.getAnnotationName().equals(ReportAsSingleViolation.class.getName())) {
                    return true;
                }
            }
        }
        return reflectionSupport.reportsAsSingleViolation((Class<? extends Annotation>) type);
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
        return ValidationMetadataSupport.create(reflectionSupport, type, annotationValue);
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

    /**
     * @return Whether a configuration redefining the validators of a built-in constraint kept the built-in ones
     */
    boolean includesBuiltInValidators() {
        return builtInValidators;
    }

    /**
     * @param owner      The owner of the validator instances
     * @param type       The validated type
     * @param target     The target validated
     * @return The validator resolved for them before, or null
     */
    io.micronaut.validation.validator.constraints.@Nullable ConstraintValidator<Annotation, ?> resolvedValidator(Object owner, Class<?> type, ConstraintTarget target) {
        ResolvedValidator resolved = resolvedValidator;
        return resolved != null && resolved.owner() == owner && resolved.type() == type && resolved.target() == target
            ? resolved.validator() : null;
    }

    /**
     * Remembers the validator resolved for this constraint.
     *
     * @param owner     The owner of the validator instances
     * @param type      The validated type
     * @param target    The target validated
     * @param validator The validator
     */
    void resolvedValidator(Object owner, Class<?> type, ConstraintTarget target, io.micronaut.validation.validator.constraints.ConstraintValidator<Annotation, ?> validator) {
        resolvedValidator = new ResolvedValidator(owner, type, target, validator);
    }

    /**
     * Two descriptors of one occurrence of a constraint are equal: the same constraint, the same attributes, read
     * from the same element. A descriptor built again for an element, as a validation without a constraint cache
     * does, equals the one built before, and so do the violations reporting them.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof DefaultConstraintDescriptor<?> that
            && type == that.type
            && annotationMetadata == that.annotationMetadata
            && annotationValue.equals(that.annotationValue);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + annotationValue.hashCode();
    }

    @Override
    public Map<String, Object> getAttributes() {
        var reflected = reflectionSupport.annotationAttributes(type, annotationValue);
        if (reflected != null) {
            return reflected;
        }
        return metadataAttributes();
    }

    @Override
    public Map<String, Object> messageAttributes() {
        if (annotationValue.stringValues(ValidationAnnotationUtil.RUNTIME_ATTRIBUTES).length == 0) {
            return getAttributes();
        }
        try {
            return getAttributes();
        } catch (ValidationException e) {
            // an enum or annotation member without its typed value: the message renders the metadata's
            return metadataAttributes();
        }
    }

    private Map<String, Object> metadataAttributes() {
        final Map<?, ?> values = annotationValue.getValues();
        Map<String, Object> variables = CollectionUtils.newLinkedHashMap(values.size());
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!entry.getKey().toString().startsWith("$")) {
                variables.put(entry.getKey().toString(), entry.getValue());
            }
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
        ValidationMetadataSupport.standardDefaults(type).forEach((name, value) -> variables.putIfAbsent(name.toString(), value));
        variables.put("groups", getGroups().contains(Default.class) && groups.isEmpty() ? new Class<?>[0] : groups.toArray(Class<?>[]::new));
        variables.put("payload", payload.toArray(Class<?>[]::new));
        if (type == Pattern.class || type == Email.class || type == URL.class) {
            variables.put("flags", patternFlags("flags"));
        }
        for (String name : annotationValue.stringValues(ValidationAnnotationUtil.PATTERN_FLAG_ARRAYS)) {
            variables.put(name, patternFlags(name));
        }
        for (String name : annotationValue.stringValues(ValidationAnnotationUtil.PATTERN_FLAGS)) {
            String flag = annotationValue.stringValue(name).orElseThrow(() ->
                new ConstraintDefinitionException("Missing pattern flag for " + type.getName() + "." + name));
            variables.put(name, patternFlag(flag));
        }
        if (validationAppliesTo != null) {
            variables.put(ATTRIBUTE_VALIDATION_APPLIES_TO, validationAppliesTo);
        }
        variables.replaceAll((name, value) -> copyAttribute(value));
        return Collections.unmodifiableMap(variables);
    }

    private Pattern.Flag[] patternFlags(String member) {
        return Arrays.stream(annotationValue.stringValues(member))
            .map(DefaultConstraintDescriptor::patternFlag)
            .toArray(Pattern.Flag[]::new);
    }

    private static Pattern.Flag patternFlag(String name) {
        return Arrays.stream(Pattern.Flag.values()).filter(flag -> flag.name().equals(name))
            .findFirst().orElseThrow(() -> new ConstraintDefinitionException("Unknown pattern flag: " + name));
    }

    private static Object copyAttribute(Object value) {
        return switch (value) {
            case AnnotationClassValue<?> reference -> reference.getType().orElseThrow(() ->
                new ConstraintDefinitionException("No generated class reference for " + reference.getName()));
            case AnnotationClassValue[] references -> Arrays.stream((AnnotationClassValue<?>[]) references)
                .map(reference -> reference.getType().orElseThrow(() ->
                    new ConstraintDefinitionException("No generated class reference for " + reference.getName())))
                .toArray(Class<?>[]::new);
            case Object[] array -> array.clone();
            case boolean[] array -> array.clone();
            case byte[] array -> array.clone();
            case short[] array -> array.clone();
            case char[] array -> array.clone();
            case int[] array -> array.clone();
            case long[] array -> array.clone();
            case float[] array -> array.clone();
            case double[] array -> array.clone();
            default -> value;
        };
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

    private Set<DefaultConstraintDescriptor<Annotation>> composingConstraints(
        Class<? extends Annotation> constraintType,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        AnnotationMetadata annotationMetadata) {
        List<AnnotationValue<?>> retained = parentAnnotationValue.getStereotypes();
        // the tree is the processor's only when the constraint contract is in it: every constraint it compiled
        // retains the contract, so anything else - no tree, or one another caller built - was never compiled
        if (retained != null && containsConstraintContract(retained)) {
            return retainedComposingConstraints(constraintType, retained, parentAnnotationValue, annotationMetadata);
        }
        return reflectedComposingConstraints(constraintType, parentAnnotationValue, annotationMetadata);
    }

    /**
     * The constraints a constraint composes, read off the retained tree the processor builds.
     *
     * <p>{@code jakarta.validation.Constraint} is marked {@link
     * io.micronaut.core.annotation.Retainable}, so a constraint the processor compiled keeps every
     * constraint it composes as an occurrence of its own, with the member overrides
     * {@code @OverridesAttribute} declares already applied - the processor maps them onto
     * {@code @AliasFor}. Reading it describes a composed constraint without loading the annotation
     * type back and reading its members reflectively, which is the path every constraint of a
     * compiled application takes.
     */
    private Set<DefaultConstraintDescriptor<Annotation>> retainedComposingConstraints(
        Class<? extends Annotation> constraintType,
        List<AnnotationValue<?>> retained,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        AnnotationMetadata annotationMetadata) {
        List<RetainedComposing> composing = new ArrayList<>();
        boolean directKnown = parentAnnotationValue.contains(ValidationAnnotationUtil.DIRECT_COMPOSING_CONSTRAINTS);
        List<String> direct = List.of(parentAnnotationValue.stringValues(ValidationAnnotationUtil.DIRECT_COMPOSING_CONSTRAINTS));
        for (AnnotationValue<?> stereotype : retained) {
            if (isRetainedConstraint(stereotype) && (!directKnown || direct.contains(stereotype.getAnnotationName()))) {
                composing.add(new RetainedComposing(
                                ConstraintContainers.constraintType(reflectionSupport,
                                        stereotype, constraintType.getClassLoader()), stereotype));
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
     * The rules of a composition the retained tree can answer: a composed constraint and the
     * constraints composing it share a validation target, and a member overriding a member of a
     * composing constraint has the type of that member - the tree carries the value written with
     * the type it was written with, next to the default of the member it overrides. The rules only
     * the declared form of the annotation type answers are left to the reflection module, where it
     * is present.
     */
    private void checkRetainedComposition(Class<? extends Annotation> constraintType,
                                                 AnnotationValue<? extends Annotation> parentAnnotationValue,
                                                 List<RetainedComposing> composing) {
        if (composing.isEmpty()) {
            return;
        }
        Set<ValidationTarget> common =
                new LinkedHashSet<>(ConstraintValidatorTargetResolver.constraintTargets(reflectionSupport, parentAnnotationValue, constraintType));
        for (RetainedComposing constraint : composing) {
            common.retainAll(ConstraintValidatorTargetResolver.constraintTargets(reflectionSupport, constraint.value(), constraint.type()));
            if (common.isEmpty()) {
                throw new ConstraintDefinitionException(
                        "Composing constraints must share a validation target with the composed"
                                + " constraint: "
                                + constraintType.getName());
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
        reflectionSupport.checkComposition(constraintType, parentAnnotationValue);
    }

    /**
     * Whether a value written for a member is of the kind of the member's default: the metadata
     * stores a value with the type of the member it was written for, so a String written over an
     * int member is a String next to an Integer default, and an array over a single-valued member
     * an array next to a value.
     */
    private static boolean sameKind(Object value, Object defaultValue) {
        Class<?> valueType = value.getClass();
        Class<?> defaultType = defaultValue.getClass();
        if (valueType.isArray() || defaultType.isArray()) {
            // an empty array says nothing of its component type: the metadata writes one for a
            // member left empty
            return valueType.isArray() && defaultType.isArray()
                && (valueType.getComponentType() == defaultType.getComponentType() || isEmptyArray(value) || isEmptyArray(defaultValue));
        }
        return valueType == defaultType;
    }

    private static boolean isEmptyArray(Object array) {
        return array instanceof Object[] objects && objects.length == 0;
    }

    /**
     * A composing constraint the tree names, loaded through the loader of the composed constraint:
     * the one the application sees the composition through. A type the validator's own loader can
     * also see, as in a test archive, is another class to a caller comparing them.
     */
    @SuppressWarnings("unchecked")
    private static boolean containsConstraintContract(List<AnnotationValue<?>> retained) {
        for (AnnotationValue<?> stereotype : retained) {
            if (CONSTRAINT_ANNOTATION.equals(stereotype.getAnnotationName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a retained occurrence is a constraint: the constraint contract is among its own
     * stereotypes. The contract itself keeps no subtree, so it is not taken for one of the
     * constraints it marks.
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
    private DefaultConstraintDescriptor<Annotation> retainedComposingConstraint(
        Class<? extends Annotation> annotationType,
        AnnotationValue<?> composing,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        AnnotationMetadata annotationMetadata) {
        String name = composing.getAnnotationName();
        // the values the composing annotation sets, the overrides of the composed one applied; the reserved
        // member holding the subtree is not one of them
        Map<CharSequence, Object> values = new LinkedHashMap<>(composing.getValues());
        values.remove(AnnotationUtil.STEREOTYPES_MEMBER);
        Map<CharSequence, Object> defaultValues = composing.getDefaultValues() == null ? Map.of() : composing.getDefaultValues();
        // the target of the composed constraint applies to the composing ones declaring one
        ConstraintTarget validationAppliesTo =
                Objects.requireNonNullElse(
                        ValidationEnumValues.target(parentAnnotationValue),
                        ConstraintTarget.IMPLICIT);
        if (validationAppliesTo != ConstraintTarget.IMPLICIT
            && (composing.contains(ATTRIBUTE_VALIDATION_APPLIES_TO) || defaultValues.containsKey(ATTRIBUTE_VALIDATION_APPLIES_TO))) {
            values.put(ATTRIBUTE_VALIDATION_APPLIES_TO, validationAppliesTo);
        }
        Class<?>[] parentGroups = parentAnnotationValue.classValues(ATTRIBUTE_GROUPS);
        values.put(
                ATTRIBUTE_GROUPS,
                parentGroups.length == 0
                        ? new Class<?>[] {Default.class}
                        : parentGroups);
        values.put(ATTRIBUTE_PAYLOAD, parentAnnotationValue.classValues(ATTRIBUTE_PAYLOAD));
        AnnotationValue<Annotation> annotationValue = (AnnotationValue<Annotation>) ConstraintContainers.withValidators(reflectionSupport,
            new AnnotationValue<>(name, values, defaultValues, composing.getRetentionPolicy(), composing.getStereotypes()),
            annotationType
        );
        return of(reflectionSupport, (Class<Annotation>) annotationType, annotationValue, annotationMetadata, null);
    }

    /**
     * The constraints a constraint composes where the processors retained no tree of them - the
     * constraint of a library, or of a type the processors never saw - read from the annotations of
     * the constraint type by the reflection module where it is present, and none otherwise.
     */
    @SuppressWarnings("unchecked")
    private Set<DefaultConstraintDescriptor<Annotation>> reflectedComposingConstraints(
        Class<? extends Annotation> constraintType,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        AnnotationMetadata annotationMetadata) {
        Set<DefaultConstraintDescriptor<Annotation>> composingConstraints = new LinkedHashSet<>();
        for (ReflectionSupport.ComposingConstraint composing : reflectionSupport.composingConstraints(constraintType, parentAnnotationValue)) {
            composingConstraints.add(of(reflectionSupport, (Class<Annotation>) composing.type(), composing.value(), annotationMetadata, null));
        }
        return Collections.unmodifiableSet(composingConstraints);
    }

    /**
     * A constraint the retained tree names as composing another, with its type loaded.
     *
     * @param type The composing constraint type
     * @param value The occurrence in the tree, the overrides of the composed constraint applied
     */
    private record RetainedComposing(Class<? extends Annotation> type, AnnotationValue<?> value) {
    }

    private record ResolvedValidator(Object owner, Class<?> type, ConstraintTarget target, io.micronaut.validation.validator.constraints.ConstraintValidator<Annotation, ?> validator) {
    }
}
