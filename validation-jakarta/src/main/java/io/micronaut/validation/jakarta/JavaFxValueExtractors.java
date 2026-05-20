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
package io.micronaut.validation.jakarta;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.UnwrapByDefault;
import jakarta.validation.valueextraction.ValueExtractor;
import javafx.beans.property.ListProperty;
import javafx.beans.property.MapProperty;
import javafx.beans.property.SetProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.collections.ObservableSet;

/**
 * JavaFX value extractors for the opt-in Jakarta compliance aggregate.
 *
 * @since 5.1
 */
final class JavaFxValueExtractors {

    private static final String ITERABLE_ELEMENT_NODE_NAME = "<iterable element>";
    private static final String LIST_ELEMENT_NODE_NAME = "<list element>";
    private static final String MAP_KEY_NODE_NAME = "<map key>";
    private static final String MAP_VALUE_NODE_NAME = "<map value>";

    private JavaFxValueExtractors() {
    }

    @Singleton
    @UnwrapByDefault
    @Requires(classes = ObservableValue.class)
    static final class ObservableValueExtractor implements ValueExtractor<ObservableValue<@ExtractedValue ?>> {

        @Override
        public void extractValues(ObservableValue<?> originalValue, ValueReceiver receiver) {
            receiver.value(null, originalValue == null ? null : originalValue.getValue());
        }
    }

    @Singleton
    @UnwrapByDefault
    @Requires(classes = ListProperty.class)
    static final class ListPropertyUnwrapExtractor implements ValueExtractor<@ExtractedValue(type = ObservableList.class) ListProperty<?>> {

        @Override
        public void extractValues(ListProperty<?> originalValue, ValueReceiver receiver) {
            receiver.value(null, originalValue == null ? null : originalValue.getValue());
        }
    }

    @Singleton
    @Requires(classes = ListProperty.class)
    static final class ListPropertyElementExtractor implements ValueExtractor<ListProperty<@ExtractedValue ?>> {

        @Override
        public void extractValues(ListProperty<?> originalValue, ValueReceiver receiver) {
            if (originalValue == null) {
                return;
            }
            int i = 0;
            for (Object value : originalValue) {
                receiver.indexedValue(LIST_ELEMENT_NODE_NAME, i++, value);
            }
        }
    }

    @Singleton
    @UnwrapByDefault
    @Requires(classes = SetProperty.class)
    static final class SetPropertyUnwrapExtractor implements ValueExtractor<@ExtractedValue(type = ObservableSet.class) SetProperty<?>> {

        @Override
        public void extractValues(SetProperty<?> originalValue, ValueReceiver receiver) {
            receiver.value(null, originalValue == null ? null : originalValue.getValue());
        }
    }

    @Singleton
    @Requires(classes = SetProperty.class)
    static final class SetPropertyElementExtractor implements ValueExtractor<SetProperty<@ExtractedValue ?>> {

        @Override
        public void extractValues(SetProperty<?> originalValue, ValueReceiver receiver) {
            if (originalValue == null) {
                return;
            }
            for (Object value : originalValue) {
                receiver.iterableValue(ITERABLE_ELEMENT_NODE_NAME, value);
            }
        }
    }

    @Singleton
    @UnwrapByDefault
    @Requires(classes = MapProperty.class)
    static final class MapPropertyUnwrapExtractor implements ValueExtractor<@ExtractedValue(type = ObservableMap.class) MapProperty<?, ?>> {

        @Override
        public void extractValues(MapProperty<?, ?> originalValue, ValueReceiver receiver) {
            receiver.value(null, originalValue == null ? null : originalValue.getValue());
        }
    }

    @Singleton
    @Requires(classes = MapProperty.class)
    static final class MapPropertyKeyExtractor implements ValueExtractor<MapProperty<@ExtractedValue ?, ?>> {

        @Override
        public void extractValues(MapProperty<?, ?> originalValue, ValueReceiver receiver) {
            if (originalValue == null) {
                return;
            }
            for (java.util.Map.Entry<?, ?> entry : originalValue.entrySet()) {
                receiver.keyedValue(MAP_KEY_NODE_NAME, entry.getKey(), entry.getKey());
            }
        }
    }

    @Singleton
    @Requires(classes = MapProperty.class)
    static final class MapPropertyValueExtractor implements ValueExtractor<MapProperty<?, @ExtractedValue ?>> {

        @Override
        public void extractValues(MapProperty<?, ?> originalValue, ValueReceiver receiver) {
            if (originalValue == null) {
                return;
            }
            for (java.util.Map.Entry<?, ?> entry : originalValue.entrySet()) {
                receiver.keyedValue(MAP_VALUE_NODE_NAME, entry.getKey(), entry.getValue());
            }
        }
    }
}
