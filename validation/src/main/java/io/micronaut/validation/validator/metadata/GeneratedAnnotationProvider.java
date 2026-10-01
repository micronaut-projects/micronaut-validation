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
package io.micronaut.validation.validator.metadata;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;

import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

/** Factory and declaration metadata emitted by the validation processor. */
@Internal
public interface GeneratedAnnotationProvider {
    /**
     * Identifies the declaration owning this provider.
     *
     * @return The declaration owner, or null for an older provider
     */
    default @Nullable Class<?> ownerType() {
        return null;
    }

    /**
     * Creates a concrete annotation from generated member metadata.
     *
     * @param value The attributes
     * @return The concrete annotation, or null
     */
    @Nullable Annotation create(AnnotationValue<?> value);

    /**
     * Resolves a generated type reference.
     *
     * @param name The type name
     * @return Its class reference, or null
     */
    default @Nullable Class<?> type(String name) {
        return null;
    }

    /**
     * Retrieves the declared annotation members.
     *
     * @param name The annotation name
     * @return Its members, or null
     */
    default @Nullable Map<String, AnnotationMember> annotationMembers(String name) {
        return null;
    }

    /**
     * Retrieves generated enum constants without reflective discovery.
     *
     * @param name The enum name
     * @return Its constants, or null
     */
    default @Nullable List<Enum<?>> enumConstants(String name) {
        return null;
    }

    /**
     * Reads typed annotation attributes from generated metadata.
     *
     * @param value The occurrence
     * @return Its typed attributes, or null
     */
    default @Nullable Map<String, Object> attributes(AnnotationValue<?> value) {
        return null;
    }

    /**
     * Checks or retrieves generated constraint definition metadata.
     *
     * @param name The constraint name
     * @return Its definition, or null
     */
    default @Nullable GeneratedConstraintDefinition definition(String name) {
        return null;
    }

    /**
     * Allocates an array using generated component metadata.
     *
     * @param name The component name
     * @param size The size
     * @return A typed array, or null
     */
    default Object @Nullable [] array(String name, int size) {
        return null;
    }

    /**
     * Retrieves generated hierarchy and group metadata.
     *
     * @param name The type name
     * @return Its hierarchy and group metadata, or null
     */
    default @Nullable ValidationTypeMetadata typeMetadata(String name) {
        return null;
    }

    /**
     * Supplements type annotations for one declaration without mutating shared compiler types.
     *
     * @param declaration The declaration key
     * @param argument The original generated argument
     * @return The metadata-preserving argument
     */
    default Argument<?> propertyArgument(String declaration, Argument<?> argument) {
        return argument;
    }

    /**
     * Finds metadata for one executable declaration.
     *
     * @param name The method name
     * @param parameters The erased parameter types
     * @return The generated declaration, or null
     */
    default @Nullable ValidationDeclaration methodDeclaration(String name, Class<?>[] parameters) {
        return null;
    }

}
