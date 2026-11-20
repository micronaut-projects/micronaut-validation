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
import io.micronaut.validation.validator.ReflectionSupport;

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

    /**
     * Which of a type's own type variables it passes as a type argument of a generic super type, as the generated
     * introspection of the type answers it.
     *
     * @param type The type
     * @param superType The generic super type
     * @param index The type argument of the super type
     * @return The index of the type variable, {@link ReflectionSupport#BOUND_TYPE_ARGUMENT} if the type binds the
     * argument, {@link ReflectionSupport#UNDESCRIBED_TYPE_ARGUMENT} if the hierarchy does not tell
     */
    static int declaredTypeArgumentIndex(Class<?> type, Class<?> superType, int index) {
        Argument<?> resolved = ReflectionArguments.resolveGenericToArgument(type, superType);
        Argument<?>[] arguments = resolved == null ? Argument.ZERO_ARGUMENTS : resolved.getTypeParameters();
        if (index < 0 || index >= arguments.length) {
            return ReflectionSupport.UNDESCRIBED_TYPE_ARGUMENT;
        }
        // a wildcard or a type resolved in place of a variable is bound: only a variable left unresolved is one
        // the type passes on
        if (!arguments[index].isUnresolvedTypeVariable()) {
            return ReflectionSupport.BOUND_TYPE_ARGUMENT;
        }
        if (!(arguments[index] instanceof GenericPlaceholder<?> placeholder)) {
            return ReflectionSupport.UNDESCRIBED_TYPE_ARGUMENT;
        }
        TypeVariable<?>[] own = type.getTypeParameters();
        for (int i = 0; i < own.length; i++) {
            if (own[i].getName().equals(placeholder.getVariableName())) {
                return i;
            }
        }
        return ReflectionSupport.UNDESCRIBED_TYPE_ARGUMENT;
    }
}
