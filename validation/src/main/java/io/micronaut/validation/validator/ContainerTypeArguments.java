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
package io.micronaut.validation.validator;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.reflection.ReflectionArguments;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Maps the type argument a value extractor extracts onto the type arguments of the container as declared:
 * a container type may bind or rename the type arguments of the generic type the extractor is written for.
 *
 * @since 5.0.0
 */
@Internal
final class ContainerTypeArguments {

    private ContainerTypeArguments() {
    }

    /**
     * The type a type binds the type argument of a generic super type to, with the annotations declared on it
     * and its own type arguments: read from the annotated super types first, which carry the annotations,
     * else resolved through the hierarchy, which substitutes the type variables.
     */
    @Nullable
    static Argument<?> resolveBoundTypeArgument(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        if (declaredType == containerType || !containerType.isAssignableFrom(declaredType)) {
            return null;
        }
        Argument<?> annotated = annotatedBoundTypeArgument(declaredType, containerType, typeArgumentIndex);
        if (annotated != null) {
            return annotated;
        }
        Argument<?> resolved = ReflectionArguments.resolveGenericToArgument(declaredType, containerType);
        Argument<?>[] typeParameters = resolved.getTypeParameters();
        return typeArgumentIndex < typeParameters.length && typeParameters[typeArgumentIndex].getType() != Object.class
            ? typeParameters[typeArgumentIndex]
            : null;
    }

    @Nullable
    private static Argument<?> annotatedBoundTypeArgument(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        List<AnnotatedType> supertypes = new ArrayList<>();
        supertypes.add(declaredType.getAnnotatedSuperclass());
        supertypes.addAll(List.of(declaredType.getAnnotatedInterfaces()));
        for (AnnotatedType supertype : supertypes) {
            if (supertype == null) {
                continue;
            }
            if (supertype instanceof AnnotatedParameterizedType parameterizedType
                && parameterizedType.getType() instanceof ParameterizedType type
                && type.getRawType() == containerType) {
                AnnotatedType bound = parameterizedType.getAnnotatedActualTypeArguments()[typeArgumentIndex];
                return bound.getType() instanceof TypeVariable<?> ? null : ReflectionArguments.of(bound);
            }
            Class<?> rawSupertype = supertype.getType() instanceof ParameterizedType type && type.getRawType() instanceof Class<?> raw ? raw
                : supertype.getType() instanceof Class<?> supertypeClass ? supertypeClass : null;
            if (rawSupertype != null && rawSupertype != Object.class && containerType.isAssignableFrom(rawSupertype)) {
                Argument<?> resolved = annotatedBoundTypeArgument(rawSupertype, containerType, typeArgumentIndex);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    static Integer resolveExtractedTypeArgumentIndex(Class<?> declaredType,
                                                     Class<?> extractorContainerType,
                                                             Integer extractorTypeArgumentIndex) {
        if (extractorTypeArgumentIndex == null || declaredType == extractorContainerType) {
            return extractorTypeArgumentIndex;
        }
        Integer resolved = resolveExtractedTypeArgumentIndex(declaredType, declaredType.getGenericSuperclass(), extractorContainerType, extractorTypeArgumentIndex);
        if (resolved != null) {
            return resolved;
        }
        for (Type genericInterface : declaredType.getGenericInterfaces()) {
            resolved = resolveExtractedTypeArgumentIndex(declaredType, genericInterface, extractorContainerType, extractorTypeArgumentIndex);
            if (resolved != null) {
                return resolved;
            }
        }
        return extractorTypeArgumentIndex;
    }

    private static Integer resolveExtractedTypeArgumentIndex(Class<?> declaredType,
                                                             Type genericType,
                                                             Class<?> extractorContainerType,
                                                             int extractorTypeArgumentIndex) {
        if (!(genericType instanceof ParameterizedType parameterizedType) || parameterizedType.getRawType() != extractorContainerType) {
            return null;
        }
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        if (extractorTypeArgumentIndex >= actualTypeArguments.length) {
            return null;
        }
        Type actualTypeArgument = actualTypeArguments[extractorTypeArgumentIndex];
        if (actualTypeArgument instanceof TypeVariable<?> typeVariable) {
            TypeVariable<?>[] declaredTypeParameters = declaredType.getTypeParameters();
            for (int i = 0; i < declaredTypeParameters.length; i++) {
                if (Objects.equals(declaredTypeParameters[i].getName(), typeVariable.getName())) {
                    return i;
                }
            }
        }
        return extractorTypeArgumentIndex;
    }
}
