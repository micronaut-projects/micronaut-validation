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
import io.micronaut.validation.annotation.NotInEnum;
import jakarta.inject.Singleton;


/**
 * Validator for the {@link NotInEnum} constraint.
 */
@Singleton
public class NotInEnumValidator implements ConstraintValidator<NotInEnum, Object> {

    @Override
    public boolean isValid(@Nullable Object value,
                           @NonNull AnnotationValue<NotInEnum> annotationMetadata,
                           @NonNull ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        @SuppressWarnings("unchecked") Class<? extends Enum<?>> enumClass =
            (Class<? extends Enum<?>>) annotationMetadata.classValue("value", Enum.class).orElse(null);
        if (enumClass == null) {
            return true; // Invalid configuration, pass validation
        }

        if (value instanceof Enum<?> && enumClass.isInstance(value)) {
            return true;
        } else if (value instanceof String stringValue) {
            boolean caseSensitive = annotationMetadata.booleanValue("caseSensitive").orElse(true);

            Enum<?>[] constants = enumClass.getEnumConstants();
            if (constants == null) {
                return true; // Invalid enum class, pass validation
            }
            for (Enum<?> constant : constants) {
                String name = constant.name();
                if (caseSensitive ? name.equals(stringValue) : name.equalsIgnoreCase(stringValue)) {
                    return false; // Match found, validation fails
                }
            }
            return true; // No match, validation passes
        } else {
            return true;
        }
    }
}
