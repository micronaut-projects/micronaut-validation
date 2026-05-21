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

import io.micronaut.validation.validator.constraints.ConstraintValidatorTargetResolver;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.UnexpectedTypeException;
import jakarta.validation.constraintvalidation.ValidationTarget;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reflection-only Jakarta constraint validator resolution.
 *
 * <p>This class implements the specification's runtime validator selection
 * rules for constraints discovered without Micronaut metadata. Keep it isolated
 * to this module so the default validator does not need generic signature
 * reflection.</p>
 *
 * @since 5.1
 */
final class ReflectionConstraintValidatorResolution {

    private ReflectionConstraintValidatorResolution() {
    }

    /**
     * Resolves the single most-specific validator class for a reflected
     * constraint and validated type.
     *
     * @param constraintType The constraint annotation type, used for diagnostics
     * @param validatorTypes Candidate validator classes declared by the
     * constraint or XML metadata
     * @param valueType The runtime value type being validated
     * @param constraintTarget Whether generic or cross-parameter validation is
     * being resolved
     * @return The selected validator class, or {@code null} when no candidate
     * applies
     */
    @Nullable
    static Class<? extends jakarta.validation.ConstraintValidator<?, ?>> resolve(
        Class<?> constraintType,
        List<Class<? extends jakarta.validation.ConstraintValidator<?, ?>>> validatorTypes,
        Class<?> valueType,
        ConstraintTarget constraintTarget) {
        Class<?> resolvedValueType = ConstraintValidatorTargetResolver.resolveTargetType(valueType);
        List<Candidate> candidates = new ArrayList<>();
        for (Class<? extends jakarta.validation.ConstraintValidator<?, ?>> validatorType : validatorTypes) {
            Candidate candidate = Candidate.of(validatorType);
            if (candidate.supports(constraintTarget) && candidate.validatedType.isAssignableFrom(resolvedValueType)) {
                candidates.add(candidate);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        List<Candidate> maximallySpecific = candidates.stream()
            .filter(candidate -> candidates.stream().noneMatch(other -> candidate != other && candidate.isLessSpecificThan(other)))
            .toList();
        if (maximallySpecific.size() != 1) {
            throw new UnexpectedTypeException("Cannot resolve a unique constraint validator for constraint: " + constraintType.getName() + " and type: " + valueType.getName());
        }
        return maximallySpecific.get(0).validatorType;
    }

    private static boolean supportsParameters(Class<?> validatorType) {
        return ConstraintValidatorTargetResolver.validationTargets(validatorType).contains(ValidationTarget.PARAMETERS);
    }

    private static boolean supportsAnnotatedElement(Class<?> validatorType) {
        Set<ValidationTarget> validationTargets = ConstraintValidatorTargetResolver.validationTargets(validatorType);
        return validationTargets.isEmpty() || validationTargets.contains(ValidationTarget.ANNOTATED_ELEMENT);
    }

    private record Candidate(
        Class<? extends jakarta.validation.ConstraintValidator<?, ?>> validatorType,
        Class<?> validatedType,
        boolean parameters,
        boolean annotatedElement
    ) {

        static Candidate of(Class<? extends jakarta.validation.ConstraintValidator<?, ?>> validatorType) {
            return new Candidate(
                validatorType,
                ConstraintValidatorTargetResolver.getTargetType(validatorType),
                supportsParameters(validatorType),
                supportsAnnotatedElement(validatorType)
            );
        }

        boolean supports(ConstraintTarget constraintTarget) {
            if (constraintTarget == ConstraintTarget.PARAMETERS) {
                return parameters;
            }
            return annotatedElement;
        }

        boolean isLessSpecificThan(Candidate other) {
            return validatedType != other.validatedType && validatedType.isAssignableFrom(other.validatedType);
        }
    }
}
