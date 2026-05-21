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
import jakarta.validation.ConstraintTarget;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
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

    @Nullable
    private static Class<?> findTargetType(Class<?> type) {
        for (Type genericInterface : type.getGenericInterfaces()) {
            Class<?> targetType = findTargetType(genericInterface);
            if (targetType != null) {
                return targetType;
            }
        }
        Type genericSuperclass = type.getGenericSuperclass();
        return genericSuperclass == null ? null : findTargetType(genericSuperclass);
    }

    @Nullable
    private static Class<?> findTargetType(Type type) {
        if (type instanceof ParameterizedType parameterizedType) {
            Class<?> targetType = constraintValidatorTargetType(parameterizedType);
            if (targetType != null) {
                return targetType;
            }
            Type rawType = parameterizedType.getRawType();
            if (rawType instanceof Class<?> rawClass) {
                return findTargetType(rawClass);
            }
            return null;
        }
        if (type instanceof Class<?> clazz && clazz != Object.class) {
            return findTargetType(clazz);
        }
        return null;
    }

    @Nullable
    private static Class<?> constraintValidatorTargetType(ParameterizedType parameterizedType) {
        Type rawType = parameterizedType.getRawType();
        if (rawType == ConstraintValidator.class || rawType == jakarta.validation.ConstraintValidator.class) {
            Type[] typeArguments = parameterizedType.getActualTypeArguments();
            if (typeArguments.length == 2) {
                return typeArgumentType(typeArguments[1]);
            }
        }
        return null;
    }

    @Nullable
    private static Class<?> typeArgumentType(Type typeArgument) {
        if (typeArgument instanceof Class<?> aClass) {
            return aClass;
        }
        if (typeArgument instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> rawClass) {
            return rawClass;
        }
        return null;
    }
}
