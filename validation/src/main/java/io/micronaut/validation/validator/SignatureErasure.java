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

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;

/**
 * Erases a signature a caller holds. It reads nothing from a class, so it needs no reflection module, and it is
 * the one place of this module allowed to take a {@link Type} apart.
 *
 * @since 5.3.0
 */
@Internal
final class SignatureErasure {

    private SignatureErasure() {
    }

    /**
     * @param type The signature
     * @return The raw class, {@code Object[]} for a generic array
     */
    static Class<?> erase(Type type) {
        if (type instanceof Class<?> classType) {
            return classType;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return erase(parameterizedType.getRawType());
        }
        if (type instanceof GenericArrayType) {
            return Object[].class;
        }
        if (type instanceof WildcardType wildcardType) {
            return erase(wildcardType.getUpperBounds()[0]);
        }
        throw new IllegalArgumentException("Unknown type: " + type);
    }
}
