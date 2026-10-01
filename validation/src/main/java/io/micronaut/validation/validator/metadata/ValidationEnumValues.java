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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;

import jakarta.validation.ConstraintTarget;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/** Reads fixed Jakarta enum contracts without reflective enum conversion. */
@Internal
public final class ValidationEnumValues {
    private ValidationEnumValues() { }

    /**
     * Reads the validation target without reflective enum lookup.
     *
     * @param value The constraint occurrence
     * @return Its requested target, or null
     */
    public static @Nullable ConstraintTarget target(AnnotationValue<?> value) {
        return switch (value.stringValue("validationAppliesTo").orElse("")) {
            case "IMPLICIT" -> ConstraintTarget.IMPLICIT;
            case "PARAMETERS" -> ConstraintTarget.PARAMETERS;
            case "RETURN_VALUE" -> ConstraintTarget.RETURN_VALUE;
            default -> null;
        };
    }

    /**
     * Reads validator targets through generated enum constants.
     *
     * @param metadata The validator declaration
     * @return Its supported targets
     */
    public static Set<ValidationTarget> targets(AnnotationMetadata metadata) {
        Set<ValidationTarget> targets = new LinkedHashSet<>();
        for (String name : metadata.stringValues(SupportedValidationTarget.class)) {
            switch (name) {
                case "ANNOTATED_ELEMENT" -> targets.add(ValidationTarget.ANNOTATED_ELEMENT);
                case "PARAMETERS" -> targets.add(ValidationTarget.PARAMETERS);
                default ->
                        throw new jakarta.validation.ConstraintDefinitionException(
                                "Unknown validation target: " + name);
            }
        }
        return Set.copyOf(targets);
    }
}
