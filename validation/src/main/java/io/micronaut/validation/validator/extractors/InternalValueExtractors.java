/*
 * Copyright 2017-2023 original authors
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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanWrapper;
import io.micronaut.core.type.Argument;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.ValueExtractor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The internal extractors.
 *
 * @author Denis Stepanov
 */
@Internal
@Introspected(accessKind = Introspected.AccessKind.FIELD)
final class InternalValueExtractors {

    /**
     * Node name for an element of an iterable.
     */
    private static final String ITERABLE_ELEMENT_NODE_NAME = "<iterable element>";
    /**
     * Node name for an element of an indexed iterable.
     */
    private static final String LIST_ELEMENT_NODE_NAME = "<list element>";
    /**
     * Node name for an key element of a map.
     */
    private static final String MAP_KEY_NODE_NAME = "<map key>";
    /**
     * Node name for an value element of a map.
     */
    private static final String MAP_VALUE_NODE_NAME = "<map value>";

    final ValueExtractor<Optional<@ExtractedValue ?>> optionalValueExtractor =
        (originalValue, receiver) -> receiver.value(null, originalValue.orElse(null));
    final UnwrapByDefaultValueExtractor<@ExtractedValue(type = Integer.class) OptionalInt> optionalIntValueExtractor =
        (originalValue, receiver) -> receiver.value(null, originalValue.isPresent() ? originalValue.getAsInt() : null);
    final UnwrapByDefaultValueExtractor<@ExtractedValue(type = Long.class)  OptionalLong> optionalLongValueExtractor =
        (originalValue, receiver) -> receiver.value(null, originalValue.isPresent() ? originalValue.getAsLong() : null);
    final UnwrapByDefaultValueExtractor<@ExtractedValue(type = Double.class)  OptionalDouble> optionalDoubleValueExtractor =
        (originalValue, receiver) -> receiver.value(null, originalValue.isPresent() ? originalValue.getAsDouble() : null);

    final ValueExtractor<Iterable<@ExtractedValue ?>> iterableValueExtractor = (originalValue, receiver) -> {
        if (originalValue instanceof List) {
            int i = 0;
            for (Object o : originalValue) {
                receiver.indexedValue(LIST_ELEMENT_NODE_NAME, i++, o);
            }
        } else {
            for (Object o : originalValue) {
                receiver.iterableValue(ITERABLE_ELEMENT_NODE_NAME, o);
            }
        }
    };
    final ValueExtractor<Map<@ExtractedValue ?, ?>> mapKeyExtractor = (originalValue, receiver) -> {
        for (Map.Entry<?, ?> entry : originalValue.entrySet()) {
            receiver.keyedValue(MAP_KEY_NODE_NAME, entry.getKey(), entry.getKey());
        }
    };

    final ValueExtractor<Map<?, @ExtractedValue ?>> mapValueExtractor = (originalValue, receiver) -> {
        for (Map.Entry<?, ?> entry : originalValue.entrySet()) {
            receiver.keyedValue(MAP_VALUE_NODE_NAME, entry.getKey(), entry.getValue());
        }
    };

    public static List<Map.Entry<Argument<Object>, ValueExtractor<?>>> getValueExtractors() {
        InternalValueExtractors bean = new InternalValueExtractors();
        BeanWrapper<InternalValueExtractors> wrapper = BeanWrapper.findWrapper(InternalValueExtractors.class, bean).orElse(null);
        if (wrapper == null) {
            throw new IllegalArgumentException("Cannot retrieve constraint validators");
        }
        return wrapper.getBeanProperties()
            .stream()
            .<Map.Entry<Argument<Object>, ValueExtractor<?>>>map(p -> Map.entry(p.asArgument(), (ValueExtractor<?>) p.get(bean)))
            .toList();
    }

}
