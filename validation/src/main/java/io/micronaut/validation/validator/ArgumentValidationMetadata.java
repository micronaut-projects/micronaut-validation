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
package io.micronaut.validation.validator;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.validation.annotation.ValidatedElement;
import jakarta.validation.Valid;

/**
 * Queries declaration and nested type annotations without erasing argument structure.
 *
 * @since 5.3.0
 */
@Internal
final class ArgumentValidationMetadata {
    private ArgumentValidationMetadata() { }

    /**
     * Whether an argument is validated: the processor marks one with {@code ValidatedElement} when
     * it carries a constraint or a cascade, and an argument read reflectively carries the
     * constraint or the cascade itself.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     * @param containerArgument The argument
     * @param <E> The argument type
     * @return Whether it is validated
     */
    static <E> boolean isValidated(ReflectionSupport reflectionSupport, Argument<E> containerArgument) {
        return reflectionSupport.prepareArgument(containerArgument).getAnnotationMetadata().hasAnnotation(ValidatedElement.class);
    }

    /**
     * Whether a type argument of an argument is validated.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     * @param argument The argument
     * @return Whether a type argument is validated
     */
    static boolean hasValidatedTypeArgument(ReflectionSupport reflectionSupport, Argument<?> argument) {
        for (Argument<?> typeParameter : reflectionSupport.prepareArgument(argument).getTypeParameters()) {
            if (isValidated(reflectionSupport, typeParameter)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a validated type argument of an argument, at any depth, is cascaded with {@link Valid}.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     * @param argument The argument
     * @return Whether a type argument is cascaded
     */
    static boolean hasCascadedTypeArgument(ReflectionSupport reflectionSupport, Argument<?> argument) {
        for (Argument<?> typeParameter : reflectionSupport.prepareArgument(argument).getTypeParameters()) {
            if (!isValidated(reflectionSupport, typeParameter)) {
                continue;
            }
            AnnotationMetadata annotationMetadata = typeParameter.getAnnotationMetadata();
            if (annotationMetadata.hasAnnotation(Valid.class)
                    || annotationMetadata.hasStereotype(Valid.class)
                    || hasCascadedTypeArgument(reflectionSupport, typeParameter)) {
                return true;
            }
        }
        return false;
    }

}
