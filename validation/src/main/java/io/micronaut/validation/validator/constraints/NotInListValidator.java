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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.validation.annotation.NotInList;
import jakarta.inject.Singleton;

/**
 * Validator for the {@link NotInList} constraint.
 */
@Singleton
public class NotInListValidator implements ConstraintValidator<NotInList, Object> {

    @Override
    public boolean isValid(@Nullable Object value,
                           @NonNull AnnotationValue<NotInList> annotationMetadata,
                           @NonNull ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (!(value instanceof String) && !(value instanceof Enum<?>)) {
            return true;
        }
        if (value instanceof Enum<?>) {
            value = ((Enum<?>) value).name();
        }

        String[] listValues = annotationMetadata.stringValues(AnnotationMetadata.VALUE_MEMBER);
        if (listValues.length == 0) {
            return true; // Empty list, pass
        }

        boolean caseSensitive = annotationMetadata.booleanValue("caseSensitive").orElse(true);

        String stringValue = (String) value;
        for (String listValue : listValues) {
            if (caseSensitive ? stringValue.equals(listValue) : stringValue.equalsIgnoreCase(listValue)) {
                return false; // Match found, fail
            }
        }
        return true; // No match, pass
    }
}
