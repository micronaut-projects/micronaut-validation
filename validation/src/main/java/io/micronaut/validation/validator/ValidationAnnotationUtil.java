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
public final class ValidationAnnotationUtil {

    public static final String CONSTRAINT_VALIDATED_BY = "$validatedBy";
    public static final String CONSTRAINT_TYPE = "$constraintType";
    public static final String VALIDATION_TARGETS = "$validationTargets";
    public static final String REPORT_AS_SINGLE_VIOLATION = "$reportAsSingleViolation";
    public static final String COMPOSITION_ERROR = "$compositionError";
    public static final String DIRECT_COMPOSING_CONSTRAINTS = "$directComposingConstraints";
    public static final String COMPOSITION_DEFINITION_ERROR = "$compositionDefinitionError";
    public static final String DEFINITION_CHECKED = "$definitionChecked";
    public static final String DEFINITION_ERROR = "$definitionError";
    public static final String PATTERN_FLAGS = "$patternFlags";
    public static final String PATTERN_FLAG_ARRAYS = "$patternFlagArrays";
    public static final String RUNTIME_ATTRIBUTES = "$runtimeAttributes";
    /**
     * Recorded on an {@code @InEnum} or {@code @NotInEnum} occurrence: the names of the constants of its enum.
     */
    public static final String ENUM_VALUES = "$enumValues";
    /**
     * Recorded on a Jakarta constraint validator: whether it implements {@code initialize(A)} itself. One
     * that keeps the no-op default needs no annotation instance.
     */
    public static final String VALIDATOR_INITIALIZATION = "io.micronaut.validation.internal.ValidatorInitialization";

    /**
     * The containers of the JDK the validator reads without a record of their hierarchy: each passes its type
     * variables, in order, to the container type a standard value extractor is written for, and each is a
     * container type itself. The processor records the generic hierarchy of any other container it sees used
     * with validated type arguments.
     */
    public static final Set<String> POSITIONAL_JDK_CONTAINERS = Set.of(
        "java.lang.Iterable", "java.util.Collection", "java.util.List", "java.util.Set", "java.util.Map",
        "java.util.Optional", "java.util.ArrayList", "java.util.LinkedList", "java.util.HashSet",
        "java.util.LinkedHashSet", "java.util.TreeSet", "java.util.HashMap", "java.util.LinkedHashMap",
        "java.util.TreeMap", "java.util.SortedMap", "java.util.NavigableMap", "java.util.SortedSet",
        "java.util.NavigableSet");

    private ValidationAnnotationUtil() {
    }

    /**
     * Whether a type belongs to the platform or the specification APIs, which declare no constraints of the
     * application and are never introspected: a hierarchy walk leaves them aside.
     *
     * @param typeName The name of the type
     * @return Whether its package is under {@code java.}, {@code javax.} or {@code jakarta.}
     * @since 5.3.0
     */
    public static boolean isPlatformType(String typeName) {
        return typeName.startsWith("java.") || typeName.startsWith("javax.") || typeName.startsWith("jakarta.");
    }
}
