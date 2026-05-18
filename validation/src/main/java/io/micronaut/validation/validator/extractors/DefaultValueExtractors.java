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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.inject.BeanDefinition;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.valueextraction.ValueExtractor;
import jakarta.validation.valueextraction.ValueExtractorDeclarationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
            ), false);
        }
        if (beanContext != null && beanContext.containsBean(ValueExtractor.class)) {
            final Collection<BeanRegistration<ValueExtractor>> valueExtractors = beanContext.getBeanRegistrations(ValueExtractor.class);
            if (CollectionUtils.isNotEmpty(valueExtractors)) {
                for (BeanRegistration<ValueExtractor> reg : valueExtractors) {
                    BeanDefinition<ValueExtractor> beanDefinition = reg.getBeanDefinition();
                    Argument<ValueExtractor> argument = beanDefinition.asArgument();
                    if (argument.getType().equals(ValueExtractor.class)) {
                        addValueExtractor(localValueExtractors, new ValueExtractorDefinition(
                            argument,
                            reg.getBean()
                        ), false);
                    } else {
                        List<Argument<?>> typeArguments = beanDefinition.getTypeArguments(ValueExtractor.class);
                        if (typeArguments.isEmpty()) {
                            throw new IllegalStateException("No value-extractors found for bean definition: " + beanDefinition);
                        }
                        addValueExtractor(localValueExtractors, new ValueExtractorDefinition(
                            Argument.of(ValueExtractor.class, beanDefinition.getAnnotationMetadata(), typeArguments.toArray(new Argument[0])),
                            reg.getBean()
                        ), false);
                    }
                }
            }
        }
    }

    @Override
    public <T> void addValueExtractor(ValueExtractorDefinition<T> valueExtractorDefinition) {
        addValueExtractor(localValueExtractors, valueExtractorDefinition, false);
    }

    @Override
    public <T> void replaceValueExtractor(ValueExtractorDefinition<T> valueExtractorDefinition) {
        addValueExtractor(localValueExtractors, valueExtractorDefinition, true);
    }

    private <T> void addValueExtractor(Map<Class<?>, List<ValueExtractorDefinition<?>>> collection,
                                       ValueExtractorDefinition<T> valueExtractorDefinition,
                                       boolean replace) {
        List<ValueExtractorDefinition<?>> valueExtractorDefinitions = collection.computeIfAbsent(
            valueExtractorDefinition.containerType(),
            ignore -> new ArrayList<>()
        );
        boolean duplicate = valueExtractorDefinitions.stream()
            .anyMatch(def -> def.containerType().equals(valueExtractorDefinition.containerType()) && Objects.equals(def.typeArgumentIndex(), valueExtractorDefinition.typeArgumentIndex()));
        if (duplicate && !replace) {
            throw new ValueExtractorDeclarationException("Value extractor with this type and type argument is already defined!");
        }
        if (duplicate) {
            valueExtractorDefinitions.removeIf(def -> def.containerType().equals(valueExtractorDefinition.containerType()) && Objects.equals(def.typeArgumentIndex(), valueExtractorDefinition.typeArgumentIndex()));
        }
        valueExtractorDefinitions.add(valueExtractorDefinition);
        matchingValueExtractors.clear();
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public <T> List<ValueExtractorDefinition<T>> findValueExtractors(@NonNull Class<T> targetType) {
        List<ValueExtractorDefinition<?>> valueExtractorDefinitions = matchingValueExtractors.get(targetType);
        if (valueExtractorDefinitions == null) {
            valueExtractorDefinitions = findMaximallySpecificValueExtractors(targetType);
            matchingValueExtractors.put(targetType, valueExtractorDefinitions);
        }
        return (List) valueExtractorDefinitions;
    }

    private List<ValueExtractorDefinition<?>> findMaximallySpecificValueExtractors(Class<?> targetType) {
        Map<ExtractorKey, ValueExtractorDefinition<?>> candidates = new LinkedHashMap<>();
        addCandidates(candidates, localValueExtractors, targetType);
        addCandidates(candidates, internalValueExtractors, targetType);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<ValueExtractorDefinition<?>>> byTypeArgument = new LinkedHashMap<>();
        for (ValueExtractorDefinition<?> candidate : candidates.values()) {
            byTypeArgument.computeIfAbsent(candidate.typeArgumentIndex(), ignored -> new ArrayList<>()).add(candidate);
        }

        List<ValueExtractorDefinition<?>> maximallySpecific = new ArrayList<>(byTypeArgument.size());
        for (List<ValueExtractorDefinition<?>> definitions : byTypeArgument.values()) {
            List<ValueExtractorDefinition<?>> mostSpecific = definitions.stream()
                .filter(candidate -> definitions.stream().noneMatch(other -> isStrictlyMoreSpecific(other, candidate)))
                .toList();
            if (mostSpecific.size() > 1) {
                throw new ConstraintDeclarationException("There are multiple maximally specific value extractors for " + targetType.getName());
            }
            maximallySpecific.add(mostSpecific.get(0));
        }
        return List.copyOf(maximallySpecific);
    }

    private void addCandidates(Map<ExtractorKey, ValueExtractorDefinition<?>> candidates,
                               Map<Class<?>, List<ValueExtractorDefinition<?>>> valueExtractors,
                               Class<?> targetType) {
        for (Map.Entry<Class<?>, List<ValueExtractorDefinition<?>>> entry : valueExtractors.entrySet()) {
            if (entry.getKey().isAssignableFrom(targetType)) {
                for (ValueExtractorDefinition<?> definition : entry.getValue()) {
                    candidates.putIfAbsent(new ExtractorKey(definition.containerType(), definition.typeArgumentIndex()), definition);
                }
            }
        }
    }

    private static boolean isStrictlyMoreSpecific(ValueExtractorDefinition<?> candidate,
                                                  ValueExtractorDefinition<?> other) {
        return candidate != other
            && other.containerType().isAssignableFrom(candidate.containerType());
    }

    private record ExtractorKey(Class<?> containerType, Integer typeArgumentIndex) {
    }

}
