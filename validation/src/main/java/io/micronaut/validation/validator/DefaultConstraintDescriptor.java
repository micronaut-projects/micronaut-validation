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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.AnnotationMetadataSupport;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.util.CollectionUtils;
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

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
class DefaultConstraintDescriptor<T extends Annotation> implements ConstraintDescriptor<T> {

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
            annotationValue.stringValue("message").orElse(null),
            annotationValue.getDefaultValues() == null ? null : (String) annotationValue.getDefaultValues().get("message"),
            Set.of(annotationValue.classValues("groups")),
            (Set) Set.of(annotationValue.classValues("payload")),
            (List) List.of(annotationValue.classValues(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY)),
            annotationValue.enumValue("validationAppliesTo", ConstraintTarget.class).orElse(null),
            annotationValue,
            annotationMetadata);
    }

    DefaultConstraintDescriptor(@NonNull Class<T> constraintType,
                                @NonNull AnnotationValue<T> annotationValue,
                                @NonNull AnnotationMetadata annotationMetadata,
                                @NonNull List<Class<? extends ConstraintValidator<T, ?>>> validatedBy) {
        this(constraintType,
            annotationValue.stringValue("message").orElse(null),
            annotationValue.getDefaultValues() == null ? null : (String) annotationValue.getDefaultValues().get("message"),
            Set.of(annotationValue.classValues("groups")),
            (Set) Set.of(annotationValue.classValues("payload")),
            validatedBy,
            true,
            annotationValue.enumValue("validationAppliesTo", ConstraintTarget.class).orElse(null),
            annotationValue,
            annotationMetadata);
    }

    DefaultConstraintDescriptor(@NonNull Class<T> constraintType,
                                @NonNull AnnotationValue<T> annotationValue,
                                @NonNull AnnotationMetadata annotationMetadata,
                                @NonNull List<Class<? extends ConstraintValidator<T, ?>>> validatedBy,
                                boolean constraintValidatorClassesDefined) {
        this(constraintType,
            annotationValue.stringValue("message").orElse(null),
            annotationValue.getDefaultValues() == null ? null : (String) annotationValue.getDefaultValues().get("message"),
            Set.of(annotationValue.classValues("groups")),
            (Set) Set.of(annotationValue.classValues("payload")),
            validatedBy,
            constraintValidatorClassesDefined,
            annotationValue.enumValue("validationAppliesTo", ConstraintTarget.class).orElse(null),
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
        Set<DefaultConstraintDescriptor<Annotation>> composingConstraints = new LinkedHashSet<>();
        for (Annotation annotation : constraintType.getDeclaredAnnotations()) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType.isAnnotationPresent(jakarta.validation.Constraint.class)) {
                composingConstraints.add(composingConstraint(annotation, constraintType, parentAnnotationValue, annotationMetadata));
            }
        }
        return Collections.unmodifiableSet(composingConstraints);
    }

    private static DefaultConstraintDescriptor<Annotation> composingConstraint(
        Annotation annotation,
        Class<? extends Annotation> parentType,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        AnnotationMetadata annotationMetadata) {
        Class<? extends Annotation> annotationType = annotation.annotationType();
        Map<CharSequence, Object> values = annotationValues(annotation);
        applyOverrides(annotationType, parentType, parentAnnotationValue, values);
        values.put("groups", parentAnnotationValue.classValues("groups"));
        values.put("payload", parentAnnotationValue.classValues("payload"));
        AnnotationValue<Annotation> annotationValue = new AnnotationValue<>(
            annotationType.getName(),
            values,
            defaultValues(annotationType)
        );
        return new DefaultConstraintDescriptor<>((Class<Annotation>) annotationType, annotationValue, annotationMetadata);
    }

    private static void applyOverrides(
        Class<? extends Annotation> composingType,
        Class<? extends Annotation> parentType,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        Map<CharSequence, Object> values) {
        Map<String, Object> parentAttributes = attributes(parentAnnotationValue);
        for (Method method : parentType.getDeclaredMethods()) {
            Object value = parentAttributes.get(method.getName());
            if (value == null) {
                continue;
            }
            OverridesAttribute override = method.getAnnotation(OverridesAttribute.class);
            if (override != null) {
                applyOverride(composingType, values, method, value, override);
            }
            OverridesAttribute.List overrides = method.getAnnotation(OverridesAttribute.List.class);
            if (overrides != null) {
                for (OverridesAttribute listedOverride : overrides.value()) {
                    applyOverride(composingType, values, method, value, listedOverride);
                }
            }
        }
    }

    private static void applyOverride(
        Class<? extends Annotation> composingType,
        Map<CharSequence, Object> values,
        Method method,
        Object value,
        OverridesAttribute override) {
        if (override.constraint() == composingType) {
            String name = override.name().isEmpty() ? method.getName() : override.name();
            values.put(name, value);
        }
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
}
