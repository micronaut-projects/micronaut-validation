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
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.GenericPlaceholder;
import io.micronaut.reflection.ReflectionArguments;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.TypeVariable;

/**
 * Extractor-variable indexes from core's generic hierarchy resolution.
 *
 * @since 5.3.0
 */
@Internal
final class ReflectionGenericArguments {
    private ReflectionGenericArguments() {
    }

    static @Nullable Integer declaredTypeArgumentIndex(Class<?> type, Class<?> superType, int index) {
        Argument<?> resolved = ReflectionArguments.resolveGenericToArgument(type, superType);
        Argument<?>[] arguments = resolved == null ? Argument.ZERO_ARGUMENTS : resolved.getTypeParameters();
        // a wildcard or a type resolved in place of a variable is a placeholder too: only a variable left
        // unresolved is one the type passes on
        if (index < 0 || index >= arguments.length || !arguments[index].isUnresolvedTypeVariable()
            || !(arguments[index] instanceof GenericPlaceholder<?> placeholder)) {
            return null;
        }
        TypeVariable<?>[] own = type.getTypeParameters();
        for (int i = 0; i < own.length; i++) {
            if (own[i].getName().equals(placeholder.getVariableName())) {
                return i;
            }
        }
        return null;
    }
}
