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
package io.micronaut.validation.validator.constraints;

import io.micronaut.core.annotation.AnnotationValue;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in validators convert the members of an occurrence once, not on every validation.
 */
class BuiltInMemberCachingTest {

    @Test
    void anOccurrenceIsReadOnce() {
        AtomicInteger reads = new AtomicInteger();
        var cache = new AnnotationValueCache<>(value -> {
            reads.incrementAndGet();
            return new Object();
        });
        var occurrence = AnnotationValue.builder(Pattern.class).member("regexp", "a+").build();
        var equal = AnnotationValue.builder(Pattern.class).member("regexp", "a+").build();
        Object first = cache.get(occurrence);
        assertSame(first, cache.get(occurrence));
        assertEquals(1, reads.get());
        // an equal occurrence of another element is read for itself
        assertNotSame(first, cache.get(equal));
        assertEquals(2, reads.get());
    }

    @Test
    void theCompiledPatternOfAnOccurrenceIsReused() {
        var validator = new PatternValidator();
        var occurrence = AnnotationValue.builder(Pattern.class).member("regexp", "a+").build();
        assertSame(validator.getPattern(occurrence), validator.getPattern(occurrence));
        assertTrue(validator.isValid("aaa", occurrence, null));
        assertFalse(validator.isValid("b", occurrence, null));
    }

    @Test
    void anInvalidBoundFailsOnEveryValidation() {
        DecimalMinValidator<Number> validator = (value, bound) -> 0;
        var occurrence = AnnotationValue.builder(DecimalMin.class).value("x").build();
        assertThrows(ValidationException.class, () -> validator.isValid(1, occurrence, null));
        assertThrows(ValidationException.class, () -> validator.isValid(1, occurrence, null));
    }
}
