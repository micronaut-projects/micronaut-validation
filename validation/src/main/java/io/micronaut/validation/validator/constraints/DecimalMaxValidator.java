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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ConversionService;

import jakarta.validation.ValidationException;
import jakarta.validation.constraints.DecimalMax;
import java.math.BigDecimal;

/**
 * Abstract implementation of a validator for {@link DecimalMax}.
 * @param <T> The type to constrain
 *
 * @author graemerocher
 * @since 1.2
 */
public interface DecimalMaxValidator<T> extends ConstraintValidator<DecimalMax, T> {

    @Override
    default boolean isValid(@Nullable T value, @NonNull AnnotationValue<DecimalMax> annotationMetadata, @NonNull ConstraintValidatorContext context) {
        if (value == null) {
            // null considered valid according to spec
            return true;
        }

        final BigDecimal bigDecimal = annotationMetadata.getValue(String.class)
                .map(s ->
                        ConversionService.SHARED.convert(s, BigDecimal.class)
                                .orElseThrow(() -> new ValidationException(s + " does not represent a valid BigDecimal format.")))
                .orElseThrow(() -> new ValidationException("null does not represent a valid BigDecimal format."));


        int result;
        try {
            result = doComparison(value, bigDecimal);
        } catch (NumberFormatException nfe) {
            return false;
        }
        final boolean inclusive = annotationMetadata.get("inclusive", boolean.class).orElse(true);
        return inclusive ? result <= 0 : result < 0;
    }


    /**
     * Perform the comparison for the given value.
     * @param value The value
     * @param bigDecimal The big decimal
     * @return The result
     */
    int doComparison(@NonNull T value, @NonNull BigDecimal bigDecimal);
}
