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

import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintDefinitionException;

import java.lang.annotation.Annotation;

/** Checks rules requiring the declaration rather than a merged stereotype tree. */
@Internal
public final class GeneratedConstraintRules {
    private GeneratedConstraintRules() { }

    /**
     * Checks or retrieves generated constraint definition metadata.
     *
     * @param type The constraint type
     * @return Whether generated definition metadata was available
     */
    public static boolean definition(Class<? extends Annotation> type) {
        var definition = GeneratedAnnotationFactories.definition(type);
        if (definition == null) {
            return false;
        }
        if (definition.definitionError() != null) {
            throw new ConstraintDefinitionException(definition.definitionError());
        }
        return true;
    }

    /**
     * Checks generated constraint composition rules.
     *
     * @param type The composing type
     * @return Whether its declaration was generated
     */
    public static boolean composition(Class<? extends Annotation> type) {
        var definition = GeneratedAnnotationFactories.definition(type);
        if (definition == null) {
            return false;
        }
        AnnotationMetadata metadata = definition.metadata();
        for (var override : definition.overrides()) {
            if (override.error() != null) {
                throw new ConstraintDefinitionException(override.error());
            }
            int occurrences =
                    metadata.getDeclaredAnnotationValuesByType(override.constraint()).size();
            if (override.index() < -1 || override.index() >= occurrences) {
                throw new ConstraintDefinitionException(
                        "Invalid constraintIndex "
                                + override.index()
                                + " overriding "
                                + override.constraint().getName()
                                + " in "
                                + type.getName());
            }
        }
        if (definition.compositionError() != null) {
            throw new ConstraintDeclarationException(definition.compositionError());
        }
        return true;
    }
}
