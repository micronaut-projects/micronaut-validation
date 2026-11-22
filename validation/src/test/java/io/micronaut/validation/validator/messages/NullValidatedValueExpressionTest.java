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
package io.micronaut.validation.validator.messages;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.validator.DefaultValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The default interpolator renders {@code ${validatedValue}} of a null value as the empty text, the way an EL
 * interpolator, that of the reference implementation included, renders a null variable.
 */
class NullValidatedValueExpressionTest {

    @Test
    void aNullValidatedValueRendersAsEmptyText() {
        try (var factory = new DefaultValidatorFactory()) {
            List<String> messages = factory.getValidator().validate(new Subject()).stream()
                .map(ConstraintViolation::getMessage).toList();
            assertEquals(List.of("v=|"), messages);
        }
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static class Subject {
        @NotNull(message = "v=${validatedValue}|")
        public String value;
    }
}
