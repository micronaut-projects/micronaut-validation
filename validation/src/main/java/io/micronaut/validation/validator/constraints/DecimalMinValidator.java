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
package io.micronaut.validation.validator.constraints;

import io.micronaut.core.annotation.AnnotationValue;
import org.jspecify.annotations.Nullable;

import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * Abstract implementation of a validator for {@link DecimalMin}.
 *
 * @param <T> The target type.
 * @since 1.2
 * @author graemerocher
 */
@FunctionalInterface
public interface DecimalMinValidator<T> extends ConstraintValidator<DecimalMin, T> {

    @Override
    default boolean isValid(@Nullable T value, AnnotationValue<DecimalMin> annotationMetadata, ConstraintValidatorContext context) {
        // the bound of the occurrence, parsed once
        final ConstraintMembers.DecimalLimit limit = ConstraintMembers.decimalLimit(annotationMetadata);
        if (value == null) {
            // null considered valid according to spec
            return true;
        }

        final BigDecimal bigDecimal = limit.bound();


        int result;
        try {
            result = doComparison(value, bigDecimal);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return limit.inclusive() ? result >= 0 : result > 0;
    }

    /**
     * Perform the comparison for the given value.
     * @param value The value
     * @param bigDecimal The big decimal
     * @return The result
     */
    int doComparison(T value, BigDecimal bigDecimal);
}
