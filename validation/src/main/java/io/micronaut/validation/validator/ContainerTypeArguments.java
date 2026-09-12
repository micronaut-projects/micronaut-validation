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

    private ContainerTypeArguments() {
    }

    /**
     * The type a type binds the type argument of a generic super type to, with the annotations declared on it
     * and its own type arguments.
     */
    @Nullable
    static Argument<?> resolveBoundTypeArgument(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        if (declaredType == containerType || !containerType.isAssignableFrom(declaredType)) {
            return null;
        }
        return ReflectionSupport.get().boundTypeArgument(declaredType, containerType, typeArgumentIndex);
    }

    /**
     * Which of a type's own type arguments carries the one an extractor extracts.
     */
    @Nullable
    static Integer resolveExtractedTypeArgumentIndex(Class<?> declaredType,
                                                     Class<?> extractorContainerType,
                                                     @Nullable Integer extractorTypeArgumentIndex) {
        if (extractorTypeArgumentIndex == null || declaredType == extractorContainerType) {
            return extractorTypeArgumentIndex;
        }
        return ReflectionSupport.get().extractedTypeArgumentIndex(declaredType, extractorContainerType, extractorTypeArgumentIndex);
    }
}
