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
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;

import java.util.StringJoiner;

/**
 * Violation identity used to merge generated and reflection validation results.
 *
 * <p>The key intentionally captures only the stable identity fields needed to
 * suppress duplicate violations when generated metadata and reflection fallback
 * both report the same constraint.</p>
 *
 * @param constraintType The constraint annotation type
 * @param path The violation path key
 * @since 5.1
 */
@Internal
record ReflectionViolationKey(
    String constraintType,
    String path
) {

    /**
     * Creates a merge key from a validation violation.
     *
     * @param violation The violation produced by generated or reflection
     * validation
     * @return A stable identity key for duplicate suppression
     */
    static ReflectionViolationKey of(ConstraintViolation<?> violation) {
        ConstraintDescriptor<?> descriptor = violation.getConstraintDescriptor();
        return new ReflectionViolationKey(
            descriptor.getAnnotation().annotationType().getName(),
            pathKey(violation.getPropertyPath())
        );
    }

    private static String pathKey(Path path) {
        StringJoiner joiner = new StringJoiner("/");
        for (Path.Node node : path) {
            StringBuilder builder = new StringBuilder();
            builder.append(node.getKind())
                .append(':')
                .append(node.getName())
                .append(':')
                .append(node.isInIterable())
                .append(':')
                .append(node.getKey())
                .append(':')
                .append(node.getIndex());
            appendNodeDetails(builder, node);
            joiner.add(builder);
        }
        return joiner.toString();
    }

    private static void appendNodeDetails(StringBuilder builder, Path.Node node) {
        ElementKind kind = node.getKind();
        if (kind == ElementKind.CONTAINER_ELEMENT) {
            Path.ContainerElementNode containerElementNode = node.as(Path.ContainerElementNode.class);
            appendContainerDetails(builder, containerElementNode.getContainerClass(), containerElementNode.getTypeArgumentIndex());
        } else if (kind == ElementKind.PROPERTY) {
            Path.PropertyNode propertyNode = node.as(Path.PropertyNode.class);
            appendContainerDetails(builder, propertyNode.getContainerClass(), propertyNode.getTypeArgumentIndex());
        } else if (kind == ElementKind.PARAMETER) {
            builder.append(':')
                .append(node.as(Path.ParameterNode.class).getParameterIndex());
        }
    }

    private static void appendContainerDetails(StringBuilder builder,
                                               Class<?> containerClass,
                                               Integer typeArgumentIndex) {
        builder.append(':')
            .append(containerClass == null ? null : containerClass.getName())
            .append(':')
            .append(typeArgumentIndex);
    }

}
