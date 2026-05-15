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
package io.micronaut.validation.jakarta;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JakartaAggregateTest {

    @Test
    void aggregateProvidesBootstrapReflectionAndElInterpolation() {
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            Set<ConstraintViolation<PlainBean>> violations = validatorFactory.getValidator()
                .validate(new PlainBean("ab"));

            assertEquals(1, violations.size());
            ConstraintViolation<PlainBean> violation = violations.iterator().next();
            assertEquals("name", violation.getPropertyPath().toString());
            assertEquals("length 2 must be at least 3", violation.getMessage());
        }
    }

    static final class PlainBean {
        @Size(min = 3, message = "length ${validatedValue.length()} must be at least {min}")
        private final String name;

        PlainBean(String name) {
            this.name = name;
        }
    }
}
