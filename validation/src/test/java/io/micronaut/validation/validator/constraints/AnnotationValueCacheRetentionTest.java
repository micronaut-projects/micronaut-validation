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
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the built-in validators read of an occurrence is kept as long as the occurrence lives, and no longer: an
 * occurrence of a closed factory, which may name the classes of an application loader, is not retained, and the
 * occurrences of a large application are not dropped and read again past a bound.
 */
class AnnotationValueCacheRetentionTest {

    @Test
    void anOccurrenceIsNotRetainedByTheCache() throws Exception {
        WeakReference<AnnotationValue<Size>> reference = readOnce();
        for (int attempt = 0; attempt < 100 && reference.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(reference.get(), "the cache of the built-in validators must not retain an occurrence");
    }

    private static WeakReference<AnnotationValue<Size>> readOnce() {
        AnnotationValue<Size> size = AnnotationValue.builder(Size.class).member("min", 2).member("max", 7).build();
        assertEquals(2, ConstraintMembers.size(size).min());
        return new WeakReference<>(size);
    }

    @Test
    void manyLiveOccurrencesAreEachReadOnce() {
        AtomicInteger reads = new AtomicInteger();
        AnnotationValueCache<Integer> cache = new AnnotationValueCache<>(value -> {
            reads.incrementAndGet();
            return value.intValue("min").orElse(0);
        });
        List<AnnotationValue<Size>> occurrences = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            occurrences.add(AnnotationValue.builder(Size.class).member("min", i).build());
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < occurrences.size(); i++) {
                assertEquals(i, cache.get(occurrences.get(i)));
            }
        }
        assertEquals(3000, reads.get());
    }
}
