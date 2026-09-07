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

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Map;

/**
 * The structure of a generic signature the specification API hands over as a {@link Type}: the types and
 * their type arguments, read from the object the API supplies and from nothing else. What a type binds in a
 * super type, and the annotations a signature carries, mean reading the class, which is what
 * {@link ReflectionSupport} decides and the reflection module does.
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
public final class GenericArguments {

    private GenericArguments() {
    }

    /**
     * The argument of a type: the class it erases to, with the type arguments it binds, an unbound variable
     * standing for its erasure.
     *
     * @param type The type
     * @return The argument
     */
    public static Argument<?> of(Type type) {
        return of(type, Map.of());
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
