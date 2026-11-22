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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code validateProperty} does not honor {@code @Valid}, as the specification requires, even where a cascaded bean
 * declares a constraint on a property of the same name. Hibernate Validator 9.1 reports such a constraint, as it
 * matches the constraints of cascaded beans by property name alone; this is a documented deviation from the
 * reference implementation, listed in the known differences of the differential tests.
 */
class ValidatePropertyDoesNotCascadeTest {

    @Test
    void aCascadedPropertyOfTheSameNameIsNotValidated() {
        try (var factory = new DefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var root = new Root();
            assertTrue(validator.validateProperty(root, "value").isEmpty());
            assertTrue(validator.validateProperty(root, "child").isEmpty());
            // the whole bean cascades
            assertEquals(1, validator.validate(root).size());
        }
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static class Root {
        public String value = "valid";
        @Valid
        public Child child = new Child();
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static class Child {
        @NotNull
        public String value;
    }
}
