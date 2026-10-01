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
import io.micronaut.validation.annotation.UniqueElements;
import io.micronaut.validation.validator.constraints.UniqueElementsValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UniqueElementsArraysTest {
    private final UniqueElementsValidator validator = new UniqueElementsValidator();

    @Test
    void detectsDuplicatesInEveryPrimitiveArrayType() {
        Object[] arrays = {new boolean[]{true, true}, new byte[]{1, 1}, new short[]{1, 1},
            new char[]{'a', 'a'}, new int[]{1, 1}, new long[]{1, 1},
            new float[]{Float.NaN, Float.NaN}, new double[]{Double.NaN, Double.NaN}};
        for (Object array : arrays) {
            assertFalse(validator.isValid(array, AnnotationValue.builder(UniqueElements.class).build(), null));
        }
    }

    @Test
    void preservesBoxedEqualityAndIgnoresNullElements() {
        assertTrue(validator.isValid(new double[]{0.0, -0.0}, AnnotationValue.builder(UniqueElements.class).build(), null));
        assertTrue(validator.isValid(new Object[]{null, null, "one"}, AnnotationValue.builder(UniqueElements.class).build(), null));
        assertFalse(validator.isValid(new String[]{"one", "one"}, AnnotationValue.builder(UniqueElements.class).build(), null));
    }
}
