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

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.validation.validator.ValidationAnnotationUtil;
import jakarta.validation.Constraint;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;

/**
 * What a constraint annotation type declares about itself, for a constraint the annotation processor never
 * compiled and whose occurrences therefore carry none of it.
 *
 * @since 5.2
 */
@Internal
final class ReflectedConstraints {

    private ReflectedConstraints() {
    }

    /**
     * The validator classes the annotation type declares.
     *
     * @param constraintType The constraint annotation type
     * @return The classes, empty where the type declares none
     */
    static List<Class<?>> declaredValidators(Class<? extends Annotation> constraintType) {
        Constraint constraint = constraintType.getAnnotation(Constraint.class);
        return constraint == null ? List.of() : List.of(constraint.validatedBy());
    }

    /**
     * The occurrence with the validators its annotation type declares.
     *
     * @param value          The occurrence
     * @param constraintType The constraint annotation type
     * @return The occurrence, unchanged where the type declares no validators
     */
    static AnnotationValue<? extends Annotation> withDeclaredValidators(AnnotationValue<? extends Annotation> value,
                                                                       Class<? extends Annotation> constraintType) {
        Constraint constraint = constraintType.getAnnotation(Constraint.class);
        if (constraint == null || constraint.validatedBy().length == 0) {
            return value;
        }
        AnnotationClassValue<?>[] validators = Arrays.stream(constraint.validatedBy())
            .map(AnnotationClassValue::new)
            .toArray(AnnotationClassValue[]::new);
        return AnnotationValue.builder(value).member(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY, validators).build();
    }
}
