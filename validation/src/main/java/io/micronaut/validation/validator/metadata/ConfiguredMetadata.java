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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;

import java.util.List;

/**
 * Merges the annotation metadata of an element with the one a {@link ValidationMetadataProvider} configures.
 *
 * @since 5.0.0
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
    @NonNull
    public static AnnotationMetadata merge(@NonNull List<AnnotationMetadata> levels) {
        List<AnnotationMetadata> present = levels.stream().filter(level -> !level.isEmpty()).toList();
        if (present.isEmpty()) {
            return AnnotationMetadata.EMPTY_METADATA;
        }
        if (present.size() == 1) {
            return present.get(0);
        }
        // a hierarchy reads the levels as they are: the generated metadata of a type is shared, copying it into
        // a mutable metadata would share and then alter its annotation values
        return new AnnotationMetadataHierarchy(true, present.toArray(AnnotationMetadata[]::new));
    }

    /**
     * Merges two levels of metadata.
     *
     * @param first  The first level
     * @param second The second level, winning where the same annotation is repeated
     * @return The merged metadata
     */
    @NonNull
    public static AnnotationMetadata merge(@NonNull AnnotationMetadata first, @NonNull AnnotationMetadata second) {
        return merge(List.of(first, second));
    }
}
