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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.AnnotationMetadatas;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.validation.annotation.ValidatedElement;

import java.util.List;
import java.util.Map;

/**
 * Merges the annotation metadata of an element with the one a configuration, such as an XML mapping, adds to it.
 *
 * @since 5.3.0
 */
@Internal
public final class ConfiguredMetadata {

    private ConfiguredMetadata() {
    }

    /**
     * Merges the levels of metadata into one, every annotation of it declared; the last level is read first.
     *
     * @param levels The levels, the one to win last
     * @return The merged metadata
     */
    public static AnnotationMetadata merge(List<AnnotationMetadata> levels) {
        // a hierarchy reads the levels as they are: the generated metadata of a type is shared, copying it into
        // a mutable metadata would share and then alter its annotation values
        return AnnotationMetadatas.merge(levels);
    }

    /**
     * Merges two levels of metadata.
     *
     * @param first  The first level
     * @param second The second level, winning where the same annotation is repeated
     * @return The merged metadata
     */
    public static AnnotationMetadata merge(AnnotationMetadata first, AnnotationMetadata second) {
        return merge(List.of(first, second));
    }

    /**
     * Marks the metadata of an element validated, as the processor marks an element carrying a constraint or a
     * cascade, at any depth of its type arguments.
     *
     * @param metadata The metadata
     * @return The metadata with {@link ValidatedElement} declared, itself when it already is
     */
    public static AnnotationMetadata markValidated(AnnotationMetadata metadata) {
        if (metadata.hasAnnotation(ValidatedElement.class)) {
            return metadata;
        }
        var marker = new MutableAnnotationMetadata();
        marker.addDeclaredAnnotation(ValidatedElement.class.getName(), Map.of());
        return merge(metadata, marker);
    }
}
