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
import org.jspecify.annotations.Nullable;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.GenericPlaceholder;

import java.util.List;

/**
 * Maps the type argument a value extractor extracts onto the type arguments of the container as declared: a
 * container type may bind or rename the type arguments of the generic type the extractor is written for.
 *
 * <p>A container read as the very type the extractor is written for answers from what it declares, which the
 * generated metadata describes. A container that binds the argument in a super type it does not restate does
 * not, and what it binds is read from the class, which {@link ReflectionSupport} decides: the reflection
 * module reads it, and without that module the validator says so rather than reading it here.</p>
 *
 * @since 5.0.0
 */
@Internal
final class ContainerTypeArguments {

    /** What {@link #generatedIndex} answers for a type argument the type binds rather than passes on. */
    static final int BOUND = -1;
    /** What {@link #generatedIndex} answers when the introspection does not tell. */
    static final int UNKNOWN = -2;

    private ContainerTypeArguments() {
    }

    /**
     * The type a type binds the type argument of a generic super type to, with the annotations declared on it
     * and its own type arguments.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     */
    static @Nullable Argument<?> resolveBoundTypeArgument(ReflectionSupport reflectionSupport, BeanIntrospector introspector, Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        if (declaredType == containerType || !containerType.isAssignableFrom(declaredType)) {
            return null;
        }
        var generated = introspector.findIntrospection(declaredType).orElse(null);
        if (generated != null) {
            List<Argument<?>> arguments = generated.getTypeArguments(containerType);
            if (typeArgumentIndex >= 0 && typeArgumentIndex < arguments.size()) {
                return arguments.get(typeArgumentIndex);
            }
        }
        return reflectionSupport.boundTypeArgument(declaredType, containerType, typeArgumentIndex);
    }

    /** Which of a type's own type arguments carries the one an extractor extracts.
     * @param reflectionSupport The access provider captured by the validator factory
     */
    static @Nullable Integer resolveExtractedTypeArgumentIndex(ReflectionSupport reflectionSupport, BeanIntrospector introspector, Class<?> declaredType,
                                                     Class<?> extractorContainerType,
                                                     @Nullable Integer extractorTypeArgumentIndex) {
        if (extractorTypeArgumentIndex == null || declaredType == extractorContainerType) {
            return extractorTypeArgumentIndex;
        }
        var generated = introspector.findIntrospection(declaredType).orElse(null);
        if (generated != null) {
            int index = generatedIndex(generated, extractorContainerType, extractorTypeArgumentIndex);
            if (index != UNKNOWN) {
                return index == BOUND ? null : index;
            }
        }
        return reflectionSupport.extractedTypeArgumentIndex(declaredType, extractorContainerType, extractorTypeArgumentIndex);
    }

    /**
     * Which of a type's own type variables its introspection passes as a type argument of a generic super type.
     *
     * @param introspection The introspection of the type
     * @param containerType The generic super type
     * @param typeArgumentIndex The type argument of the super type
     * @return The index of the type variable, {@link #BOUND} if the type binds the argument, or {@link #UNKNOWN}
     */
    static int generatedIndex(BeanIntrospection<?> introspection, Class<?> containerType, int typeArgumentIndex) {
        List<Argument<?>> arguments = introspection.getTypeArguments(containerType);
        if (typeArgumentIndex < 0 || typeArgumentIndex >= arguments.size()) {
            return UNKNOWN;
        }
        Argument<?> argument = arguments.get(typeArgumentIndex);
        if (!argument.isUnresolvedTypeVariable()) {
            return BOUND;
        }
        // the variable the type passes on, named as the type declares it among its own type arguments
        if (argument instanceof GenericPlaceholder<?> placeholder) {
            List<Argument<?>> own = introspection.getTypeArguments();
            for (int i = 0; i < own.size(); i++) {
                if (placeholder.getVariableName().equals(own.get(i).getName())) {
                    return i;
                }
            }
        }
        return UNKNOWN;
    }
}
