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

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.validation.validator.DefaultValidatorFactory;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrivateFieldAccessTest {
    @Test
    void privateFieldsUseTheCompanionWithoutSubstitutingADifferentlyTypedGetter() {
        try (var factory = new DefaultValidatorFactory()) {
            assertEquals(1, factory.getValidator().validate(new PrivateField()).size());
            var violations = factory.getValidator().validateProperty(new OptionalGetter(), "alpha");
            assertEquals(1, violations.size());
            assertEquals("non alpha with space", violations.iterator().next().getInvalidValue());
        }
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD, visibility = Introspected.Visibility.ANY)
    static final class PrivateField {
        @ReflectiveAccess @NotNull private String value;
    }

    @Introspected
    static final class OptionalGetter {
        @ReflectiveAccess @Pattern(regexp = "[a-z]+") private String alpha = "non alpha with space";
        public Optional<String> getAlpha() {
            return Optional.of("valid");
        }
    }
}
