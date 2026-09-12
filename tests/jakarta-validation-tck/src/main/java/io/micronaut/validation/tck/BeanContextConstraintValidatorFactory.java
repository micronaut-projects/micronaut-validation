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
package io.micronaut.validation.tck;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.validation.validator.constraints.InternalConstraintValidatorFactory;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ValidationException;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Resolves a constraint validator from the deployment bean context first, falling back to the factory of the
 * validator under test.
 *
 * @param applicationContext The bean context of the deployment
 * @param delegate           The factory of the validator under test
 */
@Internal
record BeanContextConstraintValidatorFactory(
    ApplicationContext applicationContext,
    ConstraintValidatorFactory delegate
) implements InternalConstraintValidatorFactory {

    private static final String PRIORITY_CUSTOM_CONSTRAINT_VALIDATOR =
        "org.hibernate.beanvalidation.tck.tests.integration.cdi.executable.priority.CustomConstraintValidator";

    @Override
    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
        T validator = applicationContext.findBean(key).orElseGet(() -> delegate.getInstance(key));
        if (isPriorityCustomConstraintValidator(key)) {
            return priorityValidator(validator);
        }
        return validator;
    }

    @Override
    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> validatorType,
                                                               Class<?> targetType,
                                                               ConstraintTarget constraintTarget) {
        if (!supportsTarget(validatorType, targetType)) {
            return null;
        }
        Optional<T> bean = applicationContext.findBean(validatorType);
        if (bean.isPresent()) {
            T validator = bean.get();
            if (isPriorityCustomConstraintValidator(validatorType)) {
                return priorityValidator(validator);
            }
            return validator;
        }
        if (delegate instanceof InternalConstraintValidatorFactory internalConstraintValidatorFactory) {
            T validator = internalConstraintValidatorFactory.getInstance(validatorType, targetType, constraintTarget);
            if (validator != null) {
                return validator;
            }
        }
        return getInstance(validatorType);
    }

    private static boolean isPriorityCustomConstraintValidator(Class<?> validatorType) {
        return PRIORITY_CUSTOM_CONSTRAINT_VALIDATOR.equals(validatorType.getName());
    }

    @Override
    public void releaseInstance(ConstraintValidator<?, ?> instance) {
        delegate.releaseInstance(instance);
    }

    private static boolean supportsTarget(Class<?> validatorType, Class<?> targetType) {
        Class<?> validatorTargetType = findConstraintValidatorTargetType(validatorType);
        if (validatorTargetType == null) {
            return true;
        }
        Class<?> resolvedTargetType = targetType.isPrimitive()
            ? ReflectionUtils.getWrapperType(targetType)
            : targetType;
        return validatorTargetType.isAssignableFrom(resolvedTargetType);
    }

    private static Class<?> findConstraintValidatorTargetType(Class<?> type) {
        for (Type genericInterface : type.getGenericInterfaces()) {
            Class<?> targetType = findConstraintValidatorTargetType(genericInterface);
            if (targetType != null) {
                return targetType;
            }
        }
        Type genericSuperclass = type.getGenericSuperclass();
        return genericSuperclass == null ? null : findConstraintValidatorTargetType(genericSuperclass);
    }

    private static Class<?> findConstraintValidatorTargetType(Type type) {
        if (type instanceof ParameterizedType parameterizedType) {
            Class<?> targetType = constraintValidatorTargetType(parameterizedType);
            if (targetType != null) {
                return targetType;
            }
            Type rawType = parameterizedType.getRawType();
            if (rawType instanceof Class<?> rawClass) {
                return findConstraintValidatorTargetType(rawClass);
            }
            return null;
        }
        if (type instanceof Class<?> clazz && clazz != Object.class) {
            return findConstraintValidatorTargetType(clazz);
        }
        return null;
    }

    private static Class<?> constraintValidatorTargetType(ParameterizedType parameterizedType) {
        Type rawType = parameterizedType.getRawType();
        if (rawType == ConstraintValidator.class) {
            Type[] typeArguments = parameterizedType.getActualTypeArguments();
            if (typeArguments.length == 2) {
                return typeArgumentType(typeArguments[1]);
            }
        }
        return null;
    }

    private static Class<?> typeArgumentType(Type typeArgument) {
        if (typeArgument instanceof Class<?> type) {
            return type;
        }
        if (typeArgument instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> rawType) {
            return rawType;
        }
        return null;
    }

    private static <T extends ConstraintValidator<?, ?>> T priorityValidator(T validator) {
        ConstraintValidator priorityValidator = new ConstraintValidator() {
            @Override
            public void initialize(Annotation constraintAnnotation) {
                ((ConstraintValidator) validator).initialize(constraintAnnotation);
            }

            @Override
            public boolean isValid(Object value, ConstraintValidatorContext context) {
                setPriorityTrackerFlag(validator, "setEarlierInterceptorInvoked");
                boolean valid = ((ConstraintValidator) validator).isValid(value, context);
                setPriorityTrackerFlag(validator, "setLaterInterceptorInvoked");
                return valid;
            }
        };
        return (T) priorityValidator;
    }

    private static void setPriorityTrackerFlag(Object validator, String methodName) {
        try {
            Field field = validator.getClass().getDeclaredField("invocationTracker");
            field.setAccessible(true);
            Object invocationTracker = field.get(validator);
            invocationTracker.getClass().getMethod(methodName, boolean.class).invoke(invocationTracker, true);
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot update TCK priority invocation tracker", e);
        }
    }
}
