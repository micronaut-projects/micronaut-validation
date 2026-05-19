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
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;

/**
 * Violation identity used to merge generated and reflection validation results.
 *
 * @param constraintType The constraint annotation type
 * @param path The violation path key
 * @param invalidValue The invalid value
 * @since 5.1
 */
@Internal
record ReflectionViolationKey(
    Class<? extends Annotation> constraintType,
    String path,
    @Nullable Object invalidValue
) {

    static ReflectionViolationKey of(ConstraintViolation<?> violation) {
        ConstraintDescriptor<?> descriptor = violation.getConstraintDescriptor();
        return new ReflectionViolationKey(
            descriptor.getAnnotation().annotationType(),
            pathKey(violation.getPropertyPath()),
            violation.getInvalidValue()
        );
    }

    private static String pathKey(jakarta.validation.Path path) {
        StringBuilder key = new StringBuilder();
        for (Path.Node node : path) {
            key.append(node.getKind())
                .append('|').append(node.getName())
                .append('|').append(node.isInIterable())
                .append('|').append(node.getKey())
                .append('|').append(node.getIndex())
                .append('|').append(containerClass(node))
                .append('|').append(typeArgumentIndex(node))
                .append(';');
        }
        return key.toString();
    }

    private static @Nullable Class<?> containerClass(Path.Node node) {
        if (node instanceof Path.PropertyNode propertyNode) {
            return propertyNode.getContainerClass();
        }
        if (node instanceof Path.ContainerElementNode containerElementNode) {
            return containerElementNode.getContainerClass();
        }
        return null;
    }

    private static @Nullable Integer typeArgumentIndex(Path.Node node) {
        if (node instanceof Path.PropertyNode propertyNode) {
            return propertyNode.getTypeArgumentIndex();
        }
        if (node instanceof Path.ContainerElementNode containerElementNode) {
            return containerElementNode.getTypeArgumentIndex();
        }
        return null;
    }
}
