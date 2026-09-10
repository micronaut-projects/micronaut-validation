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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * The constraint definition rules that only the annotation type itself can answer: the members it declares,
 * what its validators support, and what its {@code validationAppliesTo} may say. Reading an annotation type
 * is what this module is for, so the checks live here and the validator asks for them through the seam.
 *
 * @since 5.2
 */
@Internal
final class ReflectedConstraintDefinitions {

    private ReflectedConstraintDefinitions() {
    }

    /**
     * Validates one constraint annotation type against Jakarta Validation
     * constraint-definition rules.
     *
     * @param annotationType The annotation type being used as a constraint
     */
    static void validate(Class<? extends Annotation> annotationType) {
        Constraint constraint = annotationType.getAnnotation(Constraint.class);
        if (constraint == null) {
            return;
        }
        for (Method method : annotationType.getDeclaredMethods()) {
            if (method.getParameterCount() == 0
                && method.getName().startsWith("valid")
                && !"validationAppliesTo".equals(method.getName())) {
                throw new ConstraintDefinitionException("Constraint member names must not start with 'valid': " + annotationType.getName() + "." + method.getName());
            }
        }

        validateMessage(annotationType);
        validateGroups(annotationType);
        validatePayload(annotationType);
        ValidatorTargets validatorTargets = validatorTargets(annotationType, List.of(constraint.validatedBy()));
        validateValidationAppliesTo(annotationType, validatorTargets);
    }

    private static void validateMessage(Class<? extends Annotation> annotationType) {
        Method message = requiredMember(annotationType, "message");
        if (message.getReturnType() != String.class) {
            throw new ConstraintDefinitionException("Constraint message member must return String: " + annotationType.getName());
        }
    }

    private static void validateGroups(Class<? extends Annotation> annotationType) {
        Method groups = requiredMember(annotationType, "groups");
        if (groups.getReturnType() != Class[].class) {
            throw new ConstraintDefinitionException("Constraint groups member must return Class<?>[]: " + annotationType.getName());
        }
        Object defaultValue = groups.getDefaultValue();
        if (!(defaultValue instanceof Class<?>[] groupDefaults) || groupDefaults.length != 0) {
            throw new ConstraintDefinitionException("Constraint groups member must default to an empty array: " + annotationType.getName());
        }
    }

    private static void validatePayload(Class<? extends Annotation> annotationType) {
        Method payload = requiredMember(annotationType, "payload");
        if (payload.getReturnType() != Class[].class) {
            throw new ConstraintDefinitionException("Constraint payload member must return Class<? extends Payload>[]: " + annotationType.getName());
        }
        Object defaultValue = payload.getDefaultValue();
        if (!(defaultValue instanceof Class<?>[] payloadDefaults) || payloadDefaults.length != 0) {
            throw new ConstraintDefinitionException("Constraint payload member must default to an empty array: " + annotationType.getName());
        }
    }

    private static void validateValidationAppliesTo(Class<? extends Annotation> annotationType, ValidatorTargets validatorTargets) {
        Method validationAppliesTo = optionalMember(annotationType, "validationAppliesTo");
        if (validationAppliesTo != null) {
            if (validationAppliesTo.getReturnType() != ConstraintTarget.class) {
                throw new ConstraintDefinitionException("validationAppliesTo must return ConstraintTarget: " + annotationType.getName());
            }
            if (validationAppliesTo.getDefaultValue() != ConstraintTarget.IMPLICIT) {
                throw new ConstraintDefinitionException("validationAppliesTo must default to ConstraintTarget.IMPLICIT: " + annotationType.getName());
            }
        }

        if (validatorTargets.crossParameterValidators > 1) {
            throw new ConstraintDefinitionException("Cross-parameter constraints must not declare multiple cross-parameter validators: " + annotationType.getName());
        }
        if (validatorTargets.generic && validatorTargets.crossParameter) {
            if (validationAppliesTo == null) {
                throw new ConstraintDefinitionException("Generic and cross-parameter constraints must declare validationAppliesTo: " + annotationType.getName());
            }
        } else if (validationAppliesTo != null) {
            throw new ConstraintDefinitionException("validationAppliesTo is only allowed for constraints that are both generic and cross-parameter: " + annotationType.getName());
        }
    }

    private static ValidatorTargets validatorTargets(Class<? extends Annotation> annotationType,
                                                     List<Class<? extends jakarta.validation.ConstraintValidator<?, ?>>> validators) {
        boolean generic = false;
        boolean crossParameter = false;
        int crossParameterValidators = 0;
        for (Class<? extends jakarta.validation.ConstraintValidator<?, ?>> validator : validators) {
            List<ValidationTarget> targets = validationTargets(validator);
            if (targets.contains(ValidationTarget.ANNOTATED_ELEMENT)) {
                generic = true;
            }
            if (targets.contains(ValidationTarget.PARAMETERS)) {
                crossParameter = true;
                crossParameterValidators++;
                Class<?> validatedType = validatedType(validator);
                if (validatedType != Object.class && validatedType != Object[].class) {
                    throw new ConstraintDefinitionException(
                        "Cross-parameter validator must validate Object or Object[]: " + annotationType.getName() + " with " + validator.getName()
                    );
                }
            }
        }
        return new ValidatorTargets(generic, crossParameter, crossParameterValidators);
    }

    private static List<ValidationTarget> validationTargets(Class<?> validator) {
        SupportedValidationTarget supportedValidationTarget = validator.getAnnotation(SupportedValidationTarget.class);
        if (supportedValidationTarget == null) {
            return List.of(ValidationTarget.ANNOTATED_ELEMENT);
        }
        return Arrays.asList(supportedValidationTarget.value());
    }

    private static Class<?> validatedType(Class<?> validator) {
        // the second type argument of ConstraintValidator through every level between: a base leaving it open and a
        // sub type binding it is read as the type the sub type binds
        Argument<?> signature = ReflectionGenericArguments.resolveGenericToArgument(validator, jakarta.validation.ConstraintValidator.class);
        Argument<?>[] typeParameters = signature == null ? Argument.ZERO_ARGUMENTS : signature.getTypeParameters();
        return typeParameters.length == 2 ? typeParameters[1].getType() : Object.class;
    }

    private static Method requiredMember(Class<? extends Annotation> annotationType, String name) {
        Method member = optionalMember(annotationType, name);
        if (member == null) {
            throw new ConstraintDefinitionException("Constraint must declare member '" + name + "': " + annotationType.getName());
        }
        return member;
    }

    @Nullable
    private static Method optionalMember(Class<? extends Annotation> annotationType, String name) {
        try {
            return annotationType.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private record ValidatorTargets(boolean generic, boolean crossParameter, int crossParameterValidators) {
    }
}
