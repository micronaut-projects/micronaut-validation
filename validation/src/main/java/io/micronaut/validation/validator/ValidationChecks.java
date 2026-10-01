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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.StringUtils;

import org.jspecify.annotations.Nullable;

/** Jakarta validation preconditions, preserving the specified exception categories. */
@Internal
final class ValidationChecks {
    private ValidationChecks() { }

    /**
     * Throws a {@link IllegalArgumentException} if the value is null.
     *
     * @param name check name
     * @param value value being checked
     * @return the value
     * @param <T> value Type
     */
    static <T> T requireNonNull(String name, @Nullable T value) {
        if (value == null) {
            throw new IllegalArgumentException("Argument [" + name + "] cannot be null");
        }
        return value;
    }

    static String requireNonEmpty(String name, String value) {
        if (StringUtils.isEmpty(value)) {
            throw new IllegalArgumentException("Argument [" + name + "] cannot be empty");
        }
        return value;
    }
}
