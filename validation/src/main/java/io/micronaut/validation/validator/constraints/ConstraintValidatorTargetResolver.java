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

import io.micronaut.context.AnnotationReflectionUtils;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.type.Argument;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import org.jspecify.annotations.Nullable;

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
     * the hierarchy of the validator type by {@link AnnotationReflectionUtils#resolveGenericToArgument}, the
     * same resolution the value extractors and the generic bean arguments use.
     */
    @Nullable
    private static Class<?> findTargetType(Class<?> type) {
        Argument<ConstraintValidator> validator = AnnotationReflectionUtils.resolveGenericToArgument(type, ConstraintValidator.class);
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
