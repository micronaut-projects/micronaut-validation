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

import io.micronaut.context.ApplicationContext;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A bean that is not introspected binding the type variable of a generic interface keeps the constraint the
 * interface declares on a parameter of that type.
 */
class GenericInterfaceParameterTest {

    @Test
    void theConstraintOfTheGenericParameterIsInherited() {
        try (ApplicationContext context = ApplicationContext.run()) {
            StringRepo repo = context.getBean(StringRepo.class);
            var e = assertThrows(ConstraintViolationException.class, () -> repo.save(null));
            assertEquals(1, e.getConstraintViolations().size());
            assertEquals("save.entity", e.getConstraintViolations().iterator().next().getPropertyPath().toString());
            repo.save("value");
        }
    }

    interface Repo<T> {
        void save(@NotNull T entity);
    }

    @Singleton
    static class StringRepo implements Repo<String> {
        @Override
        public void save(@Nullable String entity) {
        }
    }
}
