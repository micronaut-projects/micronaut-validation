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
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.metadata.Scope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The descriptors of what an element declares itself are the same whenever they are asked for.
 */
class LocalElementDescriptorStabilityTest {

    @Introspected
    public static class Base {
        @NotNull
        public String getName() {
            return null;
        }
    }

    @Introspected
    public static class Sub extends Base {
        @Override
        @Size(min = 1)
        public String getName() {
            return null;
        }
    }

    @Test
    void localElementDescriptorsAreEqualAcrossCalls() {
        try (ApplicationContext context = ApplicationContext.run()) {
            var property = context.getBean(Validator.class).getConstraintsForClass(Sub.class).getConstraintsForProperty("name");
            var first = property.findConstraints().lookingAt(Scope.LOCAL_ELEMENT).getConstraintDescriptors();
            var second = property.findConstraints().lookingAt(Scope.LOCAL_ELEMENT).getConstraintDescriptors();
            assertEquals(1, first.size());
            assertEquals(first, second);
        }
    }
}
