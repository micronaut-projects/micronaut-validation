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

import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.validator.DefaultValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.Digits;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The length of the integer part of a value with a scale far below zero does not fit an int: it is counted
 * without overflowing, so that such a value has more integer digits than any bound.
 */
class DigitsLengthOverflowTest {

    @Test
    void anIntegerPartLongerThanAnIntCanCountExceedsTheBound() {
        try (var factory = new DefaultValidatorFactory()) {
            Set<String> violated = new TreeSet<>();
            for (ConstraintViolation<?> violation : factory.getValidator().validate(new Huge())) {
                violated.add(violation.getPropertyPath().toString());
            }
            assertEquals(Set.of("text", "decimal"), violated);
        }
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static class Huge {
        @Digits(integer = 3, fraction = 0) public String text = "1E2147483647";
        @Digits(integer = 3, fraction = 0) public BigDecimal decimal = new BigDecimal("1E2147483647");
        @Digits(integer = 3, fraction = 0) public BigDecimal small = new BigDecimal("1E2");
    }
}
