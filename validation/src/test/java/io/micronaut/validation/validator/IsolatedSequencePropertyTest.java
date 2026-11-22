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
import jakarta.validation.GroupSequence;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The constraints a super type redefining the default group sequence declares follow that sequence in
 * {@code validateProperty} and {@code validateValue} as they do in {@code validate}.
 */
class IsolatedSequencePropertyTest {

    @Test
    void aPropertyOfASuperTypeRedefiningTheDefaultSequenceFollowsIt() {
        try (var factory = new DefaultValidatorFactory()) {
            var validator = factory.getValidator();
            Child child = new Child();
            child.name = "ab";
            assertEquals(1, validator.validate(child).size());
            assertEquals(1, validator.validateProperty(child, "name").size());
            assertEquals(1, validator.validateValue(Child.class, "name", "ab").size());
            // the first step of the sequence fails: the second is not validated
            assertEquals(1, validator.validateValue(Child.class, "name", null).size());
        }
    }

    interface Later {
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    @GroupSequence({Parent.class, Later.class})
    static class Parent {
        @NotNull
        @Size(min = 5, groups = Later.class)
        public String name;
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static class Child extends Parent {
    }
}
