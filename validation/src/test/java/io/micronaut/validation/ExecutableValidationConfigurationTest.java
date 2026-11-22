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
package io.micronaut.validation;

import io.micronaut.context.ApplicationContext;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.executable.ExecutableType;
import jakarta.validation.executable.ValidateOnExecution;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Which executables method interception validates: by default every constrained method, getters included; with
 * the executable types of the specification configured, getters only where {@code @ValidateOnExecution} asks for
 * them; and none with executable validation disabled.
 */
class ExecutableValidationConfigurationTest {

    @Test
    void everyConstrainedMethodIsValidatedByDefault() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Service service = context.getBean(Service.class);
            assertThrows(ConstraintViolationException.class, service::getName);
            assertThrows(ConstraintViolationException.class, () -> service.greet(""));
            assertThrows(ConstraintViolationException.class, service::getExplicitName);
        }
    }

    @Test
    void gettersAreNotValidatedWithTheExecutableTypesOfTheSpecification() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "micronaut.validator.default-validated-executable-types", "CONSTRUCTORS,NON_GETTER_METHODS"))) {
            Service service = context.getBean(Service.class);
            assertDoesNotThrow(service::getName);
            assertThrows(ConstraintViolationException.class, () -> service.greet(""));
            // @ValidateOnExecution decides for the getter it annotates
            assertThrows(ConstraintViolationException.class, service::getExplicitName);
        }
    }

    @Test
    void onlyGettersAreValidatedWhenOnlyGettersAreConfigured() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "micronaut.validator.default-validated-executable-types", "GETTER_METHODS"))) {
            Service service = context.getBean(Service.class);
            assertThrows(ConstraintViolationException.class, service::getName);
            assertDoesNotThrow(() -> service.greet(""));
        }
    }

    @Test
    void nothingIsValidatedWithExecutableValidationDisabled() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "micronaut.validator.executable-validation-enabled", "false"))) {
            Service service = context.getBean(Service.class);
            assertDoesNotThrow(service::getName);
            assertDoesNotThrow(() -> service.greet(""));
            // a global switch: @ValidateOnExecution is ignored too
            assertDoesNotThrow(service::getExplicitName);
        }
    }

    @Singleton
    public static class Service {
        @NotNull
        public String getName() {
            return null;
        }

        @NotNull
        @ValidateOnExecution(type = ExecutableType.GETTER_METHODS)
        public String getExplicitName() {
            return null;
        }

        public String greet(@NotBlank String name) {
            return "Hello " + name;
        }
    }
}
