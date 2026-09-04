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
package io.micronaut.validation.validator.constraints;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.reflection.ReflectionArguments;
import org.jspecify.annotations.Nullable;

import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.UnexpectedTypeException;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Internal helpers for resolving Jakarta and Micronaut constraint validator
 * target types.
 *
 * <p>The default validator and optional reflection fallback both need the same
 * generic-signature parsing. Keep that logic here so fixes stay consistent
 * without widening the user-facing validation API.</p>
 *
 * @since 5.1
 */
@Internal
public final class ConstraintValidatorTargetResolver {

    private ConstraintValidatorTargetResolver() {
    }

    /**
     * Resolves the target type declared by a constraint validator.
     *
     * @param validatorType The validator type
     * @return The target type, or {@link Object} when it cannot be resolved
     */
    public static Class<?> getTargetType(Class<?> validatorType) {
        Class<?> targetType = findTargetType(validatorType);
        return targetType == null ? Object.class : targetType;
    }

    /**
     * Resolves primitive target types to their wrapper class.
     *
     * @param targetType The target type
     * @return The wrapper type for primitives, otherwise the original type
     */
    public static Class<?> resolveTargetType(Class<?> targetType) {
        return targetType.isPrimitive() ? ReflectionUtils.getWrapperType(targetType) : targetType;
    }

    /**
     * Reads validation target metadata from annotation metadata.
     *
     * @param annotationMetadata The annotation metadata
     * @return Supported validation targets
     */
    public static Set<ValidationTarget> validationTargets(AnnotationMetadata annotationMetadata) {
        return Set.of(annotationMetadata.enumValues(SupportedValidationTarget.class, ValidationTarget.class));
    }

    /**
     * Reads validation target metadata from the validator class.
     *
     * @param validatorType The validator type
     * @return Supported validation targets
     */
    public static Set<ValidationTarget> validationTargets(Class<?> validatorType) {
        SupportedValidationTarget supportedValidationTarget = validatorType.getAnnotation(SupportedValidationTarget.class);
        return supportedValidationTarget == null ? Set.of() : Set.of(supportedValidationTarget.value());
    }

    /**
     * Checks whether a validator can run for the requested constraint target.
     *
     * @param validationTargets Supported validation targets
     * @param constraintTarget Requested constraint target
     * @return Whether the validator supports the requested target
     */
    public static boolean allowsConstraintTarget(Set<ValidationTarget> validationTargets,
                                                 ConstraintTarget constraintTarget) {
        if (constraintTarget == ConstraintTarget.PARAMETERS && !validationTargets.contains(ValidationTarget.PARAMETERS)) {
            return false;
        }
        return constraintTarget == ConstraintTarget.PARAMETERS
            || validationTargets.isEmpty()
            || validationTargets.contains(ValidationTarget.ANNOTATED_ELEMENT);
    }

    /**
     * The type a validator validates: the second type argument of {@link ConstraintValidator}, resolved through
     * the hierarchy of the validator type by {@link ReflectionArguments#resolveGenericToArgument}, the
     * same resolution the value extractors and the generic bean arguments use.
     */
    /**
     * Checks that the {@code validationAppliesTo} of a constraint is one the element it is declared on
     * allows, as the sections 3.1.1.4 and 4.5.2.1 of the specification require: a target may only be
     * declared on an executable, {@code PARAMETERS} needs parameters, {@code RETURN_VALUE} needs a return
     * value, a constraint whose validators validate both the parameters and the return value of an
     * executable with both must declare which, and a constraint targeting the parameters needs a validator
     * that validates them.
     *
     * @param constraintType     The constraint type
     * @param validatorTypes     The validator types
     * @param validationAppliesTo The declared target, {@code null} when the member is absent
     * @param onExecutable       Whether the constraint is declared on an executable
     * @param hasParameters      Whether the executable has parameters
     * @param hasReturnValue     Whether the executable has a return value
     * @throws ConstraintDeclarationException When the target is not allowed where the constraint is declared
     * @throws ConstraintDefinitionException  When the constraint targets the parameters with no validator for them
     */
    public static void checkTargetDeclaration(Class<?> constraintType,
                                              List<? extends Class<?>> validatorTypes,
                                              @Nullable ConstraintTarget validationAppliesTo,
                                              boolean onExecutable,
                                              boolean hasParameters,
                                              boolean hasReturnValue) {
        ConstraintTarget declared = validationAppliesTo == null ? ConstraintTarget.IMPLICIT : validationAppliesTo;
        if (!onExecutable) {
            if (declared != ConstraintTarget.IMPLICIT) {
                throw new ConstraintDeclarationException("The constraint " + constraintType.getName()
                    + " declares validationAppliesTo = " + declared + " on an element that is not an executable");
            }
            return;
        }
        boolean parameters = false;
        boolean annotatedElement = false;
        for (Class<?> validatorType : validatorTypes) {
            Set<ValidationTarget> targets = validationTargets(validatorType);
            parameters |= targets.contains(ValidationTarget.PARAMETERS);
            annotatedElement |= targets.isEmpty() || targets.contains(ValidationTarget.ANNOTATED_ELEMENT);
        }
        if (declared == ConstraintTarget.PARAMETERS) {
            if (!hasParameters) {
                throw new ConstraintDeclarationException("The constraint " + constraintType.getName()
                    + " declares validationAppliesTo = PARAMETERS on an executable without parameters");
            }
            if (!parameters && !validatorTypes.isEmpty()) {
                throw new ConstraintDefinitionException("The constraint " + constraintType.getName()
                    + " targets the parameters but none of its validators supports ValidationTarget.PARAMETERS");
            }
        } else if (declared == ConstraintTarget.RETURN_VALUE) {
            if (!hasReturnValue) {
                throw new ConstraintDeclarationException("The constraint " + constraintType.getName()
                    + " declares validationAppliesTo = RETURN_VALUE on an executable without a return value");
            }
        } else if (parameters && annotatedElement && hasParameters && hasReturnValue) {
            throw new ConstraintDeclarationException("The constraint " + constraintType.getName()
                + " validates both the parameters and the return value and must declare validationAppliesTo on "
                + "an executable with parameters and a return value");
        }
    }

    /**
     * Whether at least one of the validators supports the given target: the cross-parameter phase of an
     * executable only runs the validators supporting {@link ValidationTarget#PARAMETERS}, the other phases
     * the ones supporting {@link ValidationTarget#ANNOTATED_ELEMENT}.
     *
     * @param validatorTypes   The validator types
     * @param constraintTarget The target
     * @return Whether a validator supports the target
     */
    public static boolean supportsTarget(List<? extends Class<?>> validatorTypes, ConstraintTarget constraintTarget) {
        for (Class<?> validatorType : validatorTypes) {
            if (allowsConstraintTarget(validationTargets(validatorType), constraintTarget)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Selects the validator of a constraint for a value type, as the section 4.6.4 of the specification
     * resolves it: among the validators supporting the target and accepting the type, the one whose
     * validated type is the most specific; two equally specific ones are an error.
     *
     * @param constraintType   The constraint type
     * @param validatorTypes   The validator types declared by the constraint
     * @param valueType        The type of the validated value
     * @param constraintTarget The target
     * @return The validator, or {@code null} when none accepts the type
     * @throws UnexpectedTypeException When several validators are equally specific
     */
    @Nullable
    public static Class<?> resolve(Class<?> constraintType,
                                   List<? extends Class<?>> validatorTypes,
                                   Class<?> valueType,
                                   ConstraintTarget constraintTarget) {
        Class<?> resolvedValueType = resolveTargetType(valueType);
        List<Class<?>> candidates = new ArrayList<>(validatorTypes.size());
        List<Class<?>> candidateTargets = new ArrayList<>(validatorTypes.size());
        for (Class<?> validatorType : validatorTypes) {
            if (candidates.contains(validatorType)) {
                continue;
            }
            Class<?> targetType = resolveTargetType(getTargetType(validatorType));
            if (allowsConstraintTarget(validationTargets(validatorType), constraintTarget) && targetType.isAssignableFrom(resolvedValueType)) {
                candidates.add(validatorType);
                candidateTargets.add(targetType);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Class<?> selected = null;
        for (int i = 0; i < candidates.size(); i++) {
            Class<?> target = candidateTargets.get(i);
            boolean lessSpecificThanAnother = false;
            for (int j = 0; j < candidates.size(); j++) {
                Class<?> other = candidateTargets.get(j);
                if (i != j && target != other && target.isAssignableFrom(other)) {
                    lessSpecificThanAnother = true;
                    break;
                }
            }
            if (lessSpecificThanAnother) {
                continue;
            }
            if (selected != null) {
                // two validators as specific as each other, for the same type or for unrelated ones
                throw new UnexpectedTypeException("Cannot resolve a unique constraint validator for constraint: "
                    + constraintType.getName() + " and type: " + valueType.getName());
            }
            selected = candidates.get(i);
        }
        return selected;
    }

    @Nullable
    private static Class<?> findTargetType(Class<?> type) {
        Argument<ConstraintValidator> validator = ReflectionArguments.resolveGenericToArgument(type, ConstraintValidator.class);
        if (validator == null) {
            return null;
        }
        Argument<?>[] typeParameters = validator.getTypeParameters();
        if (typeParameters.length != 2) {
            return null;
        }
        Class<?> targetType = typeParameters[1].getType();
        return targetType == Object.class ? null : targetType;
    }
}
