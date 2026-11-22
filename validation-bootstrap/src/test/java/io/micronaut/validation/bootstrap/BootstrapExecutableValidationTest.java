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
package io.micronaut.validation.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.validation.validator.ValidatorConfiguration;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.executable.ExecutableType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With the bootstrap module, method interception follows the executable validation of the bootstrap configuration:
 * without {@code META-INF/validation.xml}, the defaults of the specification, constructors and methods that are not
 * getters. A configuration property still decides over it.
 */
class BootstrapExecutableValidationTest {

    @Test
    void interceptionFollowsTheDefaultsOfTheSpecification() {
        try (ApplicationContext context = ApplicationContext.run()) {
            ValidatorConfiguration configuration = context.getBean(ValidatorConfiguration.class);
            assertTrue(configuration.isExecutableValidationEnabled());
            assertEquals(Set.of(ExecutableType.CONSTRUCTORS, ExecutableType.NON_GETTER_METHODS), configuration.getDefaultValidatedExecutableTypes());
            Service service = context.getBean(Service.class);
            assertDoesNotThrow(service::getName);
            assertThrows(ConstraintViolationException.class, () -> service.greet(""));
        }
    }

    @Test
    void aConfigurationPropertyDecidesOverTheBootstrapConfiguration() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "micronaut.validator.default-validated-executable-types", "ALL"))) {
            Service service = context.getBean(Service.class);
            assertThrows(ConstraintViolationException.class, service::getName);
            assertThrows(ConstraintViolationException.class, () -> service.greet(""));
        }
    }

    @Singleton
    public static class Service {
        @NotNull
        public String getName() {
            return null;
        }

        public String greet(@NotBlank String name) {
            return "Hello " + name;
        }
    }
}
