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
package io.micronaut.validation.reflection;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.reflection.ReflectionArguments;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps the type argument a value extractor extracts onto the type arguments of the container as
 * declared: a container type may bind or rename the type arguments of the generic type the
 * extractor is written for, and what a type binds in a super type it does not restate is not in the
 * generated metadata. Reading it is what this module is for, and the answers are cached here rather
 * than in the validator, which reads no class.
 *
 * @since 5.3.0
 */
@Internal
final class ReflectionContainerTypeArguments {

    private static final ClassValue<Map<Key, Optional<Argument<?>>>> BOUND_TYPE_ARGUMENTS =
            new ClassValue<>() {
                @Override
                protected Map<Key, Optional<Argument<?>>> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };
    private static final ClassValue<Map<Key, Optional<Integer>>> EXTRACTED_TYPE_ARGUMENT_INDEXES =
            new ClassValue<>() {
                @Override
                protected Map<Key, Optional<Integer>> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };

    private ReflectionContainerTypeArguments() { }

    /**
     * The type a type binds the type argument of a generic super type to, with the annotations
     * declared on it and its own type arguments: read from the annotated super types first, which
     * carry the annotations, else resolved through the hierarchy, which substitutes the type
     * variables.
     */
    @Nullable
    static Argument<?> boundTypeArgument(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        return BOUND_TYPE_ARGUMENTS.get(declaredType)
                .computeIfAbsent(
                        new Key(containerType, typeArgumentIndex),
                        key ->
                                Optional.ofNullable(
                                        readBoundTypeArgument(
                                                declaredType, key.containerType(), key.typeArgumentIndex()))).orElse(null);
    }

    @Nullable
    private static Argument<?> readBoundTypeArgument(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        Argument<?> resolved =
                ReflectionArguments.resolveGenericToArgument(declaredType, containerType);
        Argument<?>[] parameters =
                resolved == null ? Argument.ZERO_ARGUMENTS : resolved.getTypeParameters();
        return typeArgumentIndex >= 0 && typeArgumentIndex < parameters.length
                ? parameters[typeArgumentIndex]
                : null;
    }

    /** Which of a type's own type arguments carries the one an extractor extracts. */
    @Nullable
    static Integer extractedTypeArgumentIndex(Class<?> declaredType, Class<?> extractorContainerType, int extractorTypeArgumentIndex) {
        return EXTRACTED_TYPE_ARGUMENT_INDEXES.get(declaredType)
                .computeIfAbsent(
                        new Key(extractorContainerType, extractorTypeArgumentIndex),
                        key ->
                                Optional.ofNullable(
                                        readExtractedTypeArgumentIndex(
                                                declaredType, key.containerType(), key.typeArgumentIndex()))).orElse(null);
    }

    @Nullable
    private static Integer readExtractedTypeArgumentIndex(Class<?> declaredType, Class<?> extractorContainerType, int extractorTypeArgumentIndex) {
        Integer declared = ReflectionGenericArguments.declaredTypeArgumentIndex(declaredType, extractorContainerType, extractorTypeArgumentIndex);
        // an argument the type binds to a type, rather than passing one of its own variables on,
        // keeps the index
        return declared == null ? extractorTypeArgumentIndex : declared;
    }

    /**
     * A type, the container type it is read as, and the index of the type argument asked for.
     *
     * @param containerType The container type
     * @param typeArgumentIndex The index of the type argument
     */
    private record Key(Class<?> containerType, int typeArgumentIndex) { }
}
