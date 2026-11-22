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
package io.micronaut.validation.validator.constraints;

import jakarta.validation.ValidationException;

import java.math.BigDecimal;

/**
 * Reads the bound of {@code @DecimalMin} and {@code @DecimalMax}, which the specification gives in the string
 * representation of {@link BigDecimal}: no conversion service, the shared one or one an application configures,
 * takes part in it.
 *
 * @since 5.3.0
 */
final class DecimalBound {

    private DecimalBound() {
    }

    /**
     * @param value The bound as declared
     * @return The bound
     */
    static BigDecimal parse(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new ValidationException(value + " does not represent a valid BigDecimal format.", e);
        }
    }
}
