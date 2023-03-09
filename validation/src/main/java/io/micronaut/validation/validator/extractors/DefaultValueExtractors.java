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
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.valueextraction.UnwrapByDefault;
import jakarta.validation.valueextraction.ValueExtractor;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The default value extractors.
 *
 * @author graemerocher
 * @since 1.2
 */
@Singleton
@Introspected
public class DefaultValueExtractors implements ValueExtractorRegistry {

    private final Map<Class<?>, ValueExtractor<?>> valueExtractors;
    private final Set<Class<?>> unwrapByDefaultTypes = new HashSet<>(5);

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
        Map<Class<?>, ValueExtractor<?>> extractorMap = new HashMap<>();

        if (beanContext != null && beanContext.containsBean(ValueExtractor.class)) {
            final Collection<BeanRegistration<ValueExtractor>> valueExtractors = beanContext.getBeanRegistrations(ValueExtractor.class);
            if (CollectionUtils.isNotEmpty(valueExtractors)) {
                for (BeanRegistration<ValueExtractor> reg : valueExtractors) {
                    final ValueExtractor<?> valueExtractor = reg.getBean();
                    final Class<?>[] typeParameters = reg.getBeanDefinition().getTypeParameters(ValueExtractor.class);
                    if (ArrayUtils.isNotEmpty(typeParameters)) {
                        final Class<?> targetType = typeParameters[0];
                        extractorMap.put(targetType, valueExtractor);
                        if (valueExtractor instanceof UnwrapByDefaultValueExtractor || valueExtractor.getClass().isAnnotationPresent(UnwrapByDefault.class)) {
                            unwrapByDefaultTypes.add(targetType);
                        }
                    }
                }
            }
        }
        for (Map.Entry<Argument<Object>, ValueExtractor<?>> entry : InternalValueExtractors.getValueExtractors()) {
            final Argument<Object> definition = entry.getKey();
            final ValueExtractor<?> valueExtractor = entry.getValue();
            final Class<?> targetType = definition.getFirstTypeVariable().map(Argument::getType).orElse(null);
            extractorMap.put(targetType, valueExtractor);
            if (valueExtractor instanceof UnwrapByDefaultValueExtractor || valueExtractor.getClass().isAnnotationPresent(UnwrapByDefault.class)) {
                unwrapByDefaultTypes.add(targetType);
            }
        }
        this.valueExtractors = CollectionUtils.newHashMap(extractorMap.size());
        this.valueExtractors.putAll(extractorMap);
    }

    @Override
    public <T> void addValueExtractor(Class<T> targetType, ValueExtractor<T> valueExtractor) {
        valueExtractors.put(targetType, valueExtractor);
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public <T> Optional<ValueExtractor<T>> findValueExtractor(@NonNull Class<T> targetType) {
        final ValueExtractor<T> valueExtractor = (ValueExtractor<T>) valueExtractors.get(targetType);
        if (valueExtractor != null) {
            return Optional.of(valueExtractor);
        } else {
            return valueExtractors.entrySet().stream()
                .filter(entry -> entry.getKey().isAssignableFrom(targetType))
                .map(e -> (ValueExtractor<T>) e.getValue())
                .findFirst();
        }
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public <T> Optional<ValueExtractor<T>> findUnwrapValueExtractor(@NonNull Class<T> targetType) {
        if (unwrapByDefaultTypes.contains(targetType)) {
            return Optional.ofNullable((ValueExtractor<T>) valueExtractors.get(targetType));
        }
        return Optional.empty();
    }
}
