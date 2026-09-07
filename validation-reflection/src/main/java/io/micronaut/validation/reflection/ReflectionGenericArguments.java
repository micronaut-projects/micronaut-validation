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
import io.micronaut.validation.validator.GenericArguments;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a type binds the type arguments of a super type to, read from the class: the generated metadata
 * describes what a type declares, not what an intermediate super type it does not restate binds.
 *
 * @since 5.2
 */
@Internal
final class ReflectionGenericArguments {

    private ReflectionGenericArguments() {
    }

    /**
     * The argument of a super type as a type binds it: {@code ConstraintValidator<Size, CharSequence>} for a
     * validator declaring {@code implements ConstraintValidator<Size, CharSequence>}, through every
     * intermediate class and interface, the variables of each resolved to what its sub type binds them to.
     *
     * @param type      The type
     * @param superType The super class or interface to resolve
     * @param <T>       The super type
     * @return The argument, {@code null} when the type does not extend or implement the super type
     */
    @SuppressWarnings("unchecked")
    @Nullable
    static <T> Argument<T> resolveGenericToArgument(Class<?> type, Class<T> superType) {
        if (!superType.isAssignableFrom(type)) {
            return null;
        }
        if (type == superType) {
            return (Argument<T>) GenericArguments.of(type);
        }
        return (Argument<T>) resolve(type, superType, Map.of(), new HashSet<>());
    }

    @Nullable
    private static Argument<?> resolve(Class<?> type, Class<?> superType, Map<TypeVariable<?>, Argument<?>> bindings, Set<Class<?>> visited) {
        if (!visited.add(type)) {
            return null;
        }
        Type[] candidates = Arrays.copyOf(type.getGenericInterfaces(), type.getGenericInterfaces().length + 1);
        candidates[candidates.length - 1] = type.getGenericSuperclass();
        for (Type candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Class<?> raw = rawOf(candidate);
            if (raw == null || !superType.isAssignableFrom(raw)) {
                continue;
            }
            Argument<?> argument = of(candidate, bindings);
            if (raw == superType) {
                return argument;
            }
            Argument<?> resolved = resolve(raw, superType, bindingsOf(raw, argument), visited);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static Map<TypeVariable<?>, Argument<?>> bindingsOf(Class<?> type, Argument<?> argument) {
        TypeVariable<?>[] variables = type.getTypeParameters();
        Argument<?>[] typeParameters = argument.getTypeParameters();
        Map<TypeVariable<?>, Argument<?>> bindings = new HashMap<>();
        for (int i = 0; i < variables.length && i < typeParameters.length; i++) {
            bindings.put(variables[i], typeParameters[i]);
        }
        return bindings;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Argument<?> of(Type type, Map<TypeVariable<?>, Argument<?>> bindings) {
        if (type instanceof Class<?> clazz) {
            return Argument.of(clazz);
        }
        if (type instanceof ParameterizedType parameterized) {
            Type[] actual = parameterized.getActualTypeArguments();
            Argument<?>[] typeParameters = new Argument[actual.length];
            for (int i = 0; i < actual.length; i++) {
                typeParameters[i] = of(actual[i], bindings);
            }
            Class<?> raw = rawOf(parameterized);
            return Argument.of((Class) (raw == null ? Object.class : raw), typeParameters);
        }
        if (type instanceof TypeVariable<?> variable) {
            Argument<?> bound = bindings.get(variable);
            if (bound != null) {
                return bound;
            }
            Type[] bounds = variable.getBounds();
            return Argument.ofTypeVariable((Class) (bounds.length == 0 ? Object.class : erasureOf(bounds[0])), null, variable.getName());
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upper = wildcard.getUpperBounds();
            return of(upper.length == 0 ? Object.class : upper[0], bindings);
        }
        if (type instanceof GenericArrayType array) {
            Class<?> component = erasureOf(array.getGenericComponentType());
            return Argument.of(component.arrayType());
        }
        return Argument.OBJECT_ARGUMENT;
    }

    private static Class<?> erasureOf(Type type) {
        Class<?> raw = rawOf(type);
        return raw == null ? Object.class : raw;
    }

    @Nullable
    private static Class<?> rawOf(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof TypeVariable<?> variable) {
            Type[] bounds = variable.getBounds();
            return bounds.length == 0 ? Object.class : rawOf(bounds[0]);
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upper = wildcard.getUpperBounds();
            return upper.length == 0 ? Object.class : rawOf(upper[0]);
        }
        if (type instanceof GenericArrayType array) {
            return erasureOf(array.getGenericComponentType()).arrayType();
        }
        return null;
    }
}
