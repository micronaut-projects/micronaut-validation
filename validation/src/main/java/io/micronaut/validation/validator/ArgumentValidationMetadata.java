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
import io.micronaut.validation.validator.constraints.ConstraintContainers;

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
        AnnotationMetadata annotationMetadata = containerArgument.getAnnotationMetadata();
        return annotationMetadata.hasAnnotation(ValidatedElement.class)
                || ConstraintContainers.hasConstraints(annotationMetadata, currentClassLoader())
                || annotationMetadata.hasAnnotation(Valid.class);
    }

    static boolean hasValidatedTypeArgument(Argument<?> argument) {
        for (Argument<?> typeParameter : argument.getTypeParameters()) {
            if (isValidated(typeParameter) || hasValidatedTypeArgument(typeParameter)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasCascadedTypeArgument(Argument<?> argument) {
        for (Argument<?> typeParameter : argument.getTypeParameters()) {
            AnnotationMetadata annotationMetadata = typeParameter.getAnnotationMetadata();
            if (annotationMetadata.hasAnnotation(Valid.class)
                    || annotationMetadata.hasStereotype(Valid.class)
                    || hasCascadedTypeArgument(typeParameter)) {
                return true;
            }
        }
        return false;
    }

    private static ClassLoader currentClassLoader() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        return classLoader == null
                ? ArgumentValidationMetadata.class.getClassLoader()
                : classLoader;
    }
}
