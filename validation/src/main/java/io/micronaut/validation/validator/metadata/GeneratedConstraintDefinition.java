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

import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Immutable declaration metadata and definition diagnostics emitted by validation processors.
 *
 * @param type The constraint annotation interface
 * @param metadata Its declared annotations
 * @param definitionError A Jakarta definition error, if any
 * @param compositionError A Jakarta declaration error, if any
 * @param overrides The declared member overrides
 * @since 5.3.0
 */
@Internal
public record GeneratedConstraintDefinition(
        Class<? extends Annotation> type,
        AnnotationMetadata metadata,
        @Nullable String definitionError,
        @Nullable String compositionError,
        List<MemberOverride> overrides) {
    public GeneratedConstraintDefinition {
        overrides = List.copyOf(overrides);
    }

    /**
     * Describes one declared override of a composing constraint member.
     *
     * @param source The overriding member
     * @param constraint The composing annotation type
     * @param target The overridden member
     * @param index The occurrence index, or -1
     * @param error An invalid declaration, if any
     */
    public record MemberOverride(
            String source,
            Class<? extends Annotation> constraint,
            String target,
            int index,
            @Nullable String error) { }
}
