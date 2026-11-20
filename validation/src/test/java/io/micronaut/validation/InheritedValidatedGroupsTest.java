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
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Two beans that inherit one method under different {@link Validated} groups each validate it in their own groups.
 */
class InheritedValidatedGroupsTest {

    @Test
    void eachSubclassKeepsItsOwnGroups() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BlankChecking blank = context.getBean(BlankChecking.class);
            SizeChecking size = context.getBean(SizeChecking.class);
            // the order matters: the first call resolves the method of the parent
            assertThrows(ConstraintViolationException.class, () -> blank.accept(""));
            assertDoesNotThrow(() -> blank.accept("toolongvalue"));
            assertThrows(ConstraintViolationException.class, () -> size.accept("toolongvalue"));
            assertDoesNotThrow(() -> size.accept(""));
        }
    }

    interface BlankGroup {
    }

    interface SizeGroup {
    }

    public abstract static class Base {
        public void accept(@NotBlank(groups = BlankGroup.class) @Size(max = 3, groups = SizeGroup.class) String value) {
        }
    }

    @Singleton
    @Validated(groups = BlankGroup.class)
    public static class BlankChecking extends Base {
    }

    @Singleton
    @Validated(groups = SizeGroup.class)
    public static class SizeChecking extends Base {
    }
}
