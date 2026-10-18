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

import java.util.Set;

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
    /**
     * Recorded on a Jakarta constraint validator: whether it implements {@code initialize(A)} itself. One
     * that keeps the no-op default needs no annotation instance.
     */
    String VALIDATOR_INITIALIZATION = "io.micronaut.validation.internal.ValidatorInitialization";

    /**
     * The containers of the JDK the validator reads without a record of their hierarchy: each passes its type
     * variables, in order, to the container type a standard value extractor is written for, and each is a
     * container type itself. The processor records the generic hierarchy of any other container it sees used
     * with validated type arguments.
     */
    Set<String> POSITIONAL_JDK_CONTAINERS = Set.of(
        "java.lang.Iterable", "java.util.Collection", "java.util.List", "java.util.Set", "java.util.Map",
        "java.util.Optional", "java.util.ArrayList", "java.util.LinkedList", "java.util.HashSet",
        "java.util.LinkedHashSet", "java.util.TreeSet", "java.util.HashMap", "java.util.LinkedHashMap",
        "java.util.TreeMap", "java.util.SortedMap", "java.util.NavigableMap", "java.util.SortedSet",
        "java.util.NavigableSet");

}
