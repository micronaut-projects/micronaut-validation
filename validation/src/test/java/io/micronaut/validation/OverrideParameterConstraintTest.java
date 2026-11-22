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
import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A parameter constraint an overriding or implementing method adds is a declaration error, as the specification
 * has it. Without the reflection module the validator reads the declarations of the types with an introspection
 * only: the declaration of a type without one, a plain interface, is not seen, and the method is validated with the
 * constraints it declares, as before 5.3.
 */
class OverrideParameterConstraintTest {

    @Test
    void anImplementationAddingAParameterConstraintIsRejected() {
        try (ApplicationContext context = ApplicationContext.run()) {
            IntrospectedApiImpl introspected = context.getBean(IntrospectedApiImpl.class);
            PlainApiImpl plain = context.getBean(PlainApiImpl.class);
            assertThrows(ConstraintDeclarationException.class, () -> introspected.greet(""));
            assertThrows(ConstraintViolationException.class, () -> plain.greet(""));
        }
    }

    @Introspected
    public interface IntrospectedApi {
        void greet(String name);
    }

    public interface PlainApi {
        void greet(String name);
    }

    @Singleton
    public static class IntrospectedApiImpl implements IntrospectedApi {
        @Override
        public void greet(@NotBlank String name) {
        }
    }

    @Singleton
    public static class PlainApiImpl implements PlainApi {
        @Override
        public void greet(@NotBlank String name) {
        }
    }
}
