/*
 * Copyright 2017-2023 original authors
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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.annotation.UniqueElements;
import jakarta.inject.Singleton;

import org.jspecify.annotations.NullMarked;

import java.util.HashSet;
import java.util.Set;

/** Validator for the {@link UniqueElements} constraint. */
@Singleton
@Introspected
@NullMarked
public class UniqueElementsValidator implements ConstraintValidator<UniqueElements, Object> {

    @Override
    public boolean isValid(@Nullable Object value,
                           @NonNull AnnotationValue<UniqueElements> annotationMetadata,
                           @NonNull ConstraintValidatorContext context) {
        if (value == null) {
            return true; // Ignore null values
        }

        Set<Object> seen = new HashSet<>();

        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                if (element != null && !seen.add(element)) {
                    return false;
                }
            }
        } else if (value instanceof Object[] array) {
            for (Object element : array) {
                if (element != null && !seen.add(element)) {
                    return false;
                }
            }
        } else if (value instanceof boolean[] array) {
            for (boolean element : array) {
                if (!seen.add(element)) {
                    return false;
                }
            }
        } else if (value instanceof byte[] array) {
            for (byte element : array) {
                if (!seen.add(element)) {
                    return false;
                }
            }
        } else if (value instanceof short[] array) {
            for (short element : array) {
                if (!seen.add(element)) {
                    return false;
                }
            }
        } else if (value instanceof char[] array) {
            for (char element : array) {
                if (!seen.add(element)) {
                    return false;
                }
            }
        } else if (value instanceof int[] array) {
            for (int element : array) {
                if (!seen.add(element)) {
                    return false;
                }
            }
        } else if (value instanceof long[] array) {
            for (long element : array) {
                if (!seen.add(element)) {
                    return false;
                }
            }
        } else if (value instanceof float[] array) {
            for (float element : array) {
                if (!seen.add(element)) {
                    return false;
                }
            }
        } else if (value instanceof double[] array) {
            for (double element : array) {
                if (!seen.add(element)) {
                    return false;
                }
            }
        }
        return true;
    }
}
