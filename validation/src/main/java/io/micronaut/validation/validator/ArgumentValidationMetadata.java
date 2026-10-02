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

/** Queries declaration and nested type annotations without erasing argument structure. */
@Internal
final class ArgumentValidationMetadata {
    private ArgumentValidationMetadata() { }

    /**
     * Whether an argument is validated: the processor marks one with {@code ValidatedElement} when
     * it carries a constraint or a cascade, and an argument read reflectively carries the
     * constraint or the cascade itself.
     */
    static <E> boolean isValidated(Argument<E> containerArgument) {
        return isValidated(ReflectionSupport.get(), containerArgument);
    }

    /**
     * Whether an argument is validated: the processor marks one with {@code ValidatedElement} when
     * it carries a constraint or a cascade, and an argument read reflectively carries the
     * constraint or the cascade itself.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     */
    static <E> boolean isValidated(ReflectionSupport reflectionSupport, Argument<E> containerArgument) {
        return reflectionSupport.prepareArgument(containerArgument).getAnnotationMetadata().hasAnnotation(ValidatedElement.class);
    }

    static boolean hasValidatedTypeArgument(Argument<?> argument) {
        return hasValidatedTypeArgument(ReflectionSupport.get(), argument);
    }

    /**
     * Whether an argument is validated: the processor marks one with {@code ValidatedElement} when
     * it carries a constraint or a cascade, and an argument read reflectively carries the
     * constraint or the cascade itself.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     * @param reflectionSupport The access provider captured by the validator factory
     */
    static boolean hasValidatedTypeArgument(ReflectionSupport reflectionSupport, Argument<?> argument) {
        for (Argument<?> typeParameter : reflectionSupport.prepareArgument(argument).getTypeParameters()) {
            if (isValidated(reflectionSupport, typeParameter)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasCascadedTypeArgument(Argument<?> argument) {
        return hasCascadedTypeArgument(ReflectionSupport.get(), argument);
    }

    /**
     * Whether an argument is validated: the processor marks one with {@code ValidatedElement} when
     * it carries a constraint or a cascade, and an argument read reflectively carries the
     * constraint or the cascade itself.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     * @param reflectionSupport The access provider captured by the validator factory
     * @param reflectionSupport The access provider captured by the validator factory
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
