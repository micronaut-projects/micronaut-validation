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
package io.micronaut.validation.reflection;

import io.micronaut.context.ApplicationContext;
import io.micronaut.validation.validator.Validator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionValidatorTest {

    @Test
    void validatesBeanWithoutMicronautIntrospectionWhenReflectionFallbackIsPresent() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<PlainBean>> violations = validator.validate(new PlainBean(""));

            assertEquals(1, violations.size());
            ConstraintViolation<PlainBean> violation = violations.iterator().next();
            assertEquals("name", violation.getPropertyPath().toString());
            assertEquals("", violation.getInvalidValue());
        }
    }

    @Test
    void canDisableReflectionFallbackAtRuntime() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            ValidationException exception = assertThrows(ValidationException.class, () -> validator.validate(new PlainBean("")));

            assertTrue(exception.getMessage().contains("Bean introspection not found"));
        }
    }

    @Test
    void rejectsNullArgumentsWithIllegalArgumentException() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            assertThrows(IllegalArgumentException.class, () -> validator.validate(null));
            assertThrows(IllegalArgumentException.class, () -> validator.validateProperty(null, "name"));
            assertThrows(IllegalArgumentException.class, () -> validator.validateValue(null, "name", ""));
            assertThrows(IllegalArgumentException.class, () -> validator.getConstraintsForClass(null));
        }
    }

    static final class PlainBean {
        @NotBlank
        private final String name;

        PlainBean(String name) {
            this.name = name;
        }
    }
}
