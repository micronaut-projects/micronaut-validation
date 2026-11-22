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
package io.micronaut.validation.validator;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospection;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A null value of an annotated element is validated against the constraints of the element, as any other value.
 */
class AnnotatedElementNullValueTest {

    @Test
    void aNullValueIsValidatedAgainstTheConstraintsOfTheElement() {
        try (var validator = new DefaultValidator(new DefaultValidatorConfiguration())) {
            var property = BeanIntrospection.getIntrospection(Holder.class).getRequiredProperty("value", String.class);
            assertEquals(Set.of("must not be null"), validator.validatedAnnotatedElement(property, null));
            assertEquals(Set.of("size must be between 2 and 5"), validator.validatedAnnotatedElement(property, "x"));
            assertEquals(Set.of(), validator.validatedAnnotatedElement(property, "xyz"));
        }
    }

    @Introspected
    static final class Holder {
        private String value;

        @NotNull
        @Size(min = 2, max = 5)
        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
