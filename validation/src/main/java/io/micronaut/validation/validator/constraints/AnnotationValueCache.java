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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * What a built-in validator reads of a constraint occurrence, its members converted once: the validator is handed
 * the same annotation value on every validation of an element. Values are compared by identity, as equal values
 * of different elements may be distinct instances; the cache is dropped whole past its bound, so the occurrences
 * of XML mappings or of factories built over and over cannot grow it without end.
 *
 * @param <T> What is read
 * @since 5.3.0
 */
final class AnnotationValueCache<T> {

    private static final int MAX_ENTRIES = 1024;

    private final Map<Key, T> values = new ConcurrentHashMap<>();
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
        Key key = new Key(annotationValue);
        T value = values.get(key);
        if (value == null) {
            value = reader.apply(annotationValue);
            if (values.size() >= MAX_ENTRIES) {
                values.clear();
            }
            values.put(key, value);
        }
        return value;
    }

    /**
     * An occurrence compared by identity.
     *
     * @param value The occurrence
     */
    private record Key(AnnotationValue<?> value) {
        @Override
        @SuppressWarnings("ReferenceEquality")
        public boolean equals(@Nullable Object o) {
            return o instanceof Key other && other.value == value;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(value);
        }
    }
}
