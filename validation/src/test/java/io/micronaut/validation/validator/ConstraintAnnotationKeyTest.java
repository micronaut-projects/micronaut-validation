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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.validation.annotation.InList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Constraints that differ in their attributes have different keys, however their values print.
 */
class ConstraintAnnotationKeyTest {

    @Test
    void arrayElementsDoNotRunTogether() {
        var joined = AnnotationValue.builder(InList.class).values("a, b").build();
        var split = AnnotationValue.builder(InList.class).values("a", "b").build();
        assertNotEquals(ConstraintAnnotationKey.of(InList.class, joined), ConstraintAnnotationKey.of(InList.class, split));
    }

    @Test
    void aStringIsNotTheArrayItPrintsAs() {
        var scalar = AnnotationValue.builder(InList.class).member("value", "[a, b]").build();
        var array = AnnotationValue.builder(InList.class).values("a", "b").build();
        assertNotEquals(ConstraintAnnotationKey.of(InList.class, scalar), ConstraintAnnotationKey.of(InList.class, array));
    }

    @Test
    void equalAttributesHaveEqualKeys() {
        var first = AnnotationValue.builder(InList.class).values("a", "b").build();
        var second = AnnotationValue.builder(InList.class).values("a", "b").build();
        assertEquals(ConstraintAnnotationKey.of(InList.class, first), ConstraintAnnotationKey.of(InList.class, second));
    }
}
