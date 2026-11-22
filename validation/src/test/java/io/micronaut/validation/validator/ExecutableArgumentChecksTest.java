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
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The executable validation API rejects a parameter array that is null or does not have one value per parameter, as
 * {@code ExecutableValidator} specifies.
 */
class ExecutableArgumentChecksTest {

    @Introspected(constructors = true)
    public static class Service {
        public Service() {
        }

        public Service(@NotNull String name) {
        }

        public void save(@NotNull String name, String other) {
        }
    }

    @Test
    void aParameterArrayOfAnotherLengthIsRejected() throws Exception {
        try (var factory = new DefaultValidatorFactory()) {
            var executables = factory.getValidator().forExecutables();
            var method = Service.class.getMethod("save", String.class, String.class);
            assertEquals(0, executables.validateParameters(new Service(), method, new Object[]{"x", "y"}).size());
            assertThrows(IllegalArgumentException.class, () -> executables.validateParameters(new Service(), method, new Object[]{"x"}));
            var constructor = Service.class.getConstructor(String.class);
            assertThrows(IllegalArgumentException.class, () -> executables.validateConstructorParameters(constructor, new Object[]{}));
        }
    }

    @Test
    void aNullParameterArrayIsRejected() throws Exception {
        try (var factory = new DefaultValidatorFactory()) {
            var executables = factory.getValidator().forExecutables();
            var constructor = Service.class.getConstructor();
            assertThrows(IllegalArgumentException.class, () -> executables.validateConstructorParameters(constructor, null));
            var method = Service.class.getMethod("save", String.class, String.class);
            assertThrows(IllegalArgumentException.class, () -> executables.validateParameters(new Service(), method, null));
        }
    }
}
