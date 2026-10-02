/*
 * Copyright 2017-2023 original authors
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

import io.micronaut.core.annotation.Internal;

/**
 * Internal validation utils.
 *
 * @author Denis Stepanov
 */
@Internal
public interface ValidationAnnotationUtil {

    String CONSTRAINT_VALIDATED_BY = "$validatedBy";
    String CONSTRAINT_TYPE = "$constraintType";
    String VALIDATION_TARGETS = "$validationTargets";
    String REPORT_AS_SINGLE_VIOLATION = "$reportAsSingleViolation";
    String COMPOSITION_ERROR = "$compositionError";
    String DIRECT_COMPOSING_CONSTRAINTS = "$directComposingConstraints";
    String COMPOSITION_DEFINITION_ERROR = "$compositionDefinitionError";
    String DEFINITION_CHECKED = "$definitionChecked";
    String DEFINITION_ERROR = "$definitionError";
    String PATTERN_FLAGS = "$patternFlags";
    String PATTERN_FLAG_ARRAYS = "$patternFlagArrays";
    String RUNTIME_ATTRIBUTES = "$runtimeAttributes";

}
