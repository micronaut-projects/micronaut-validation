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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.metadata.ConstraintDescriptor;

import java.lang.annotation.Annotation;

/**
 * Violation identity used to merge generated and reflection validation results.
 *
 * @param constraintType The constraint annotation type
 * @param path The violation path key
 * @since 5.1
 */
@Internal
record ReflectionViolationKey(
    Class<? extends Annotation> constraintType,
    String path
) {

    static ReflectionViolationKey of(ConstraintViolation<?> violation) {
        ConstraintDescriptor<?> descriptor = violation.getConstraintDescriptor();
        return new ReflectionViolationKey(
            descriptor.getAnnotation().annotationType(),
            pathKey(violation.getPropertyPath())
        );
    }

    private static String pathKey(jakarta.validation.Path path) {
        return path.toString();
    }

}
