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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.util.CollectionUtils;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.Payload;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ValidateUnwrappedValue;
import jakarta.validation.valueextraction.Unwrapping;

import java.lang.annotation.Annotation;
import java.util.Collections;
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

    @NonNull
    private final Class<T> type;
    @Nullable
    private final String message;
    @Nullable
    private final String defaultMessage;
    private final Set<Class<?>> groups;
    private final Set<Class<? extends Payload>> payload;
    private final List<Class<? extends ConstraintValidator<T, ?>>> validatedBy;

    private final ConstraintTarget validationAppliesTo;
    private final AnnotationValue<T> annotationValue;
    private final AnnotationMetadata annotationMetadata;

    DefaultConstraintDescriptor(Class<T> constraintType,
                                AnnotationValue<T> annotationValue,
                                AnnotationMetadata annotationMetadata) {
        this(constraintType,
            annotationValue.stringValue("message").orElse(null),
            (String) annotationValue.getDefaultValues().get("message"),
            Set.of(annotationValue.classValues("groups")),
            (Set) Set.of(annotationValue.classValues("payload")),
            (List) List.of(annotationValue.classValues(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY)),
            annotationValue.enumValue("validationAppliesTo", ConstraintTarget.class).orElse(ConstraintTarget.IMPLICIT),
            annotationValue,
            annotationMetadata);
    }

    DefaultConstraintDescriptor(Class<T> type,
                                String message,
                                String defaultMessage,
                                Set<Class<?>> groups,
                                Set<Class<? extends Payload>> payload,
                                List<Class<? extends ConstraintValidator<T, ?>>> validatedBy,
                                ConstraintTarget validationAppliesTo,
                                AnnotationValue<T> annotationValue,
                                AnnotationMetadata annotationMetadata) {
        this.type = type;
        this.message = message;
        this.defaultMessage = defaultMessage;
        this.groups = groups;
        this.payload = payload;
        this.validatedBy = validatedBy;
        this.validationAppliesTo = validationAppliesTo;
        this.annotationValue = annotationValue;
        this.annotationMetadata = annotationMetadata;
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
        return annotationMetadata.synthesize(type);
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
    public ConstraintTarget getValidationAppliesTo() {
        return validationAppliesTo;
    }

    @Override
    public List<Class<? extends ConstraintValidator<T, ?>>> getConstraintValidatorClasses() {
        return validatedBy;
    }

    @Override
    public Map<String, Object> getAttributes() {
        final Map<?, ?> values = annotationValue.getValues();
        Map<String, Object> variables = CollectionUtils.newLinkedHashMap(values.size());
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            variables.put(entry.getKey().toString(), entry.getValue());
        }
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
        return variables;
    }

    @Override
    public Set<ConstraintDescriptor<?>> getComposingConstraints() {
        return Collections.emptySet();
    }

    @Override
    public boolean isReportAsSingleViolation() {
        return false;
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
}
