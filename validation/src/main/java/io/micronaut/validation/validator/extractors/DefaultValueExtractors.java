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

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.CollectionUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.valueextraction.ValueExtractor;
import jakarta.validation.valueextraction.ValueExtractorDeclarationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The default value extractors.
 *
 * @author graemerocher
 * @since 1.2
 */
@Internal
@Singleton
@Introspected
public final class DefaultValueExtractors implements ValueExtractorRegistry {

    private final Map<Class<?>, List<ValueExtractorDefinition<?>>> internalValueExtractors = new HashMap<>();
    private final Map<Class<?>, List<ValueExtractorDefinition<?>>> localValueExtractors = new HashMap<>();
    private final Map<Class<?>, List<ValueExtractorDefinition<?>>> matchingValueExtractors = new ConcurrentHashMap<>();

    /**
     * Default constructor.
     */
    public DefaultValueExtractors() {
        this(null);
    }

    /**
     * Constructor used during DI.
     *
     * @param beanContext The bean context
     */
    @Inject
    protected DefaultValueExtractors(@Nullable BeanContext beanContext) {
        for (Map.Entry<Argument<Object>, ValueExtractor<?>> entry : InternalValueExtractors.getValueExtractors()) {
            final Argument<Object> definition = entry.getKey();
            final ValueExtractor<?> valueExtractor = entry.getValue();
            addValueExtractor(internalValueExtractors, new ValueExtractorDefinition(
                definition,
                valueExtractor
            ));
        }
        if (beanContext != null && beanContext.containsBean(ValueExtractor.class)) {
            final Collection<BeanRegistration<ValueExtractor>> valueExtractors = beanContext.getBeanRegistrations(ValueExtractor.class);
            if (CollectionUtils.isNotEmpty(valueExtractors)) {
                for (BeanRegistration<ValueExtractor> reg : valueExtractors) {
                    addValueExtractor(localValueExtractors, new ValueExtractorDefinition(
                        reg.getBeanDefinition().asArgument(),
                        reg.getBean()
                    ));
                }
            }
        }
    }

    @Override
    public <T> void addValueExtractor(ValueExtractorDefinition<T> valueExtractorDefinition) {
        addValueExtractor(localValueExtractors, valueExtractorDefinition);
    }

    private <T> void addValueExtractor(Map<Class<?>, List<ValueExtractorDefinition<?>>> collection,
                                       ValueExtractorDefinition<T> valueExtractorDefinition) {
        List<ValueExtractorDefinition<?>> valueExtractorDefinitions = collection.computeIfAbsent(
            valueExtractorDefinition.containerType(),
            ignore -> new ArrayList<>()
        );
        if (valueExtractorDefinitions.stream()
            .anyMatch(def -> def.containerType().equals(valueExtractorDefinition.containerType()) && Objects.equals(def.typeArgumentIndex(), valueExtractorDefinition.typeArgumentIndex()))) {
            throw new ValueExtractorDeclarationException("Value extractor with this type and type argument is already defined!");
        }
        valueExtractorDefinitions.add(valueExtractorDefinition);
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public <T> List<ValueExtractorDefinition<T>> findValueExtractors(@NonNull Class<T> targetType) {
        List<ValueExtractorDefinition<?>> valueExtractorDefinitions = matchingValueExtractors.get(targetType);
        if (valueExtractorDefinitions == null) {
            valueExtractorDefinitions = localValueExtractors.get(targetType);
            if (valueExtractorDefinitions == null) {
                valueExtractorDefinitions = internalValueExtractors.get(targetType);
            }
            if (valueExtractorDefinitions == null) {
                valueExtractorDefinitions = Stream.concat(
                        localValueExtractors.entrySet().stream(),
                        internalValueExtractors.entrySet().stream()
                    )
                    .filter(entry -> entry.getKey().isAssignableFrom(targetType))
                    .map(Map.Entry::getValue)
                    .findFirst().orElseGet(List::of);
            }
            matchingValueExtractors.put(targetType, valueExtractorDefinitions);
        }
        return (List) valueExtractorDefinitions;
    }

}
