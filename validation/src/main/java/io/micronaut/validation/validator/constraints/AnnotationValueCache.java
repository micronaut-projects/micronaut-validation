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
import org.jspecify.annotations.Nullable;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * What a built-in validator reads of a constraint occurrence, its members converted once: the validator is handed
 * the same annotation value on every validation of an element. Values are compared by identity, as equal values
 * of different elements may be distinct instances, and held weakly: what is read of an occurrence lives as long as
 * the occurrence, so the occurrences of XML mappings or of closed factories, which may name the classes of an
 * application loader, are not retained, and the occurrences of a large application are not dropped and read again.
 *
 * @param <T> What is read
 * @since 5.3.0
 */
final class AnnotationValueCache<T> {

    private final Map<Object, T> values = new ConcurrentHashMap<>();
    private final ReferenceQueue<AnnotationValue<?>> collected = new ReferenceQueue<>();
    private final Function<AnnotationValue<?>, T> reader;

    /**
     * @param reader Reads an occurrence; what it throws is not cached, so it throws again for that occurrence
     */
    AnnotationValueCache(Function<AnnotationValue<?>, T> reader) {
        this.reader = reader;
    }

    /**
     * @param annotationValue The occurrence
     * @return What is read of it
     */
    T get(AnnotationValue<?> annotationValue) {
        T value = values.get(new Lookup(annotationValue));
        if (value == null) {
            expunge();
            value = reader.apply(annotationValue);
            values.put(new WeakKey(annotationValue, collected), value);
        }
        return value;
    }

    /** Drops what was read of the occurrences collected since. */
    private void expunge() {
        for (Object key = collected.poll(); key != null; key = collected.poll()) {
            values.remove(key);
        }
    }

    /**
     * An occurrence held weakly, compared by identity: once collected it equals only itself.
     */
    private static final class WeakKey extends WeakReference<AnnotationValue<?>> {
        private final int hash;

        WeakKey(AnnotationValue<?> value, ReferenceQueue<AnnotationValue<?>> queue) {
            super(value, queue);
            this.hash = System.identityHashCode(value);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        @SuppressWarnings("ReferenceEquality")
        public boolean equals(@Nullable Object o) {
            if (o == this) {
                return true;
            }
            AnnotationValue<?> value = get();
            return value != null && (o instanceof Lookup lookup ? lookup.value == value : o instanceof WeakKey other && other.get() == value);
        }
    }

    /**
     * An occurrence looked up, compared by identity with the one a key holds.
     *
     * @param value The occurrence
     */
    private record Lookup(AnnotationValue<?> value) {
        @Override
        @SuppressWarnings("ReferenceEquality")
        public boolean equals(@Nullable Object o) {
            return o instanceof WeakKey key ? key.get() == value : o instanceof Lookup other && other.value == value;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(value);
        }
    }
}
