/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.validation.validator.extractors;

import jakarta.validation.valueextraction.ValueExtractor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * Registry of value extractors.
 *
 * @author graemerocher
 * @since 1.2
 */
public interface ValueExtractorRegistry {

    <T> void addValueExtractor(ValueExtractorDefinition<T> valueExtractorDefinition);

    /**
     * Replaces a value extractor for the same container type and type argument if present.
     *
     * @param valueExtractorDefinition The value extractor definition
     * @param <T> The container type
     * @since 5.3.0
     */
    default <T> void replaceValueExtractor(ValueExtractorDefinition<T> valueExtractorDefinition) {
        addValueExtractor(valueExtractorDefinition);
    }

    /**
     * Finds {@link ValueExtractor}s for the given type.
     * @param targetType The target type of the value
     * @param <T> The target type
     * @return The extractor
     */
    @NonNull
    <T> List<ValueExtractorDefinition<T>> findValueExtractors(@NonNull Class<T> targetType);

    /**
     * Finds the maximally specific {@link ValueExtractor}s for the given type, comparing the extractors by the type
     * argument of the type each extracts: extractors declared on different super types meet only where their arguments
     * map to the same one. A registry that cannot map the arguments answers {@link #findValueExtractors(Class)}.
     *
     * @param targetType The container type
     * @param declaredTypeArgument The type argument of the container an extractor extracts, null when it binds none
     * @param <T> The container type
     * @return The extractors
     * @since 5.3.0
     */
    @NonNull
    default <T> List<ValueExtractorDefinition<T>> findValueExtractors(@NonNull Class<T> targetType,
                                                                      Function<ValueExtractorDefinition<?>, @Nullable Integer> declaredTypeArgument) {
        return findValueExtractors(targetType);
    }

}
