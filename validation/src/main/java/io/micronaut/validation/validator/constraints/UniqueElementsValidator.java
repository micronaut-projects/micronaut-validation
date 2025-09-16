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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.validation.annotation.UniqueElements;
import jakarta.inject.Singleton;

import java.lang.reflect.Array;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * Validator for the {@link UniqueElements} constraint.
 */
@Singleton
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
            return StreamSupport.stream(iterable.spliterator(), false)
                    .filter(Objects::nonNull) // Ignore null elements
                    .allMatch(seen::add);
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object element = Array.get(value, i);
                if (element != null && !seen.add(element)) {
                    return false;
                }
            }
            return true;
        } else {
            return true; // Not a collection or array, pass
        }
    }
}
