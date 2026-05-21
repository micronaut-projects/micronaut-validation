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

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.validation.validator.constraints.DefaultInternalConstraintValidatorFactory;
import io.micronaut.validation.validator.constraints.InternalConstraintValidatorFactory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ValidationException;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection fallback constraint validator factory used by the Jakarta Validation compliance stack.
 *
 * @since 5.1
 */
@Internal
@Singleton
@Replaces(DefaultInternalConstraintValidatorFactory.class)
@Requires(property = ReflectionValidator.ENABLED, notEquals = StringUtils.FALSE, defaultValue = StringUtils.TRUE)
public final class ReflectionConstraintValidatorFactory implements InternalConstraintValidatorFactory {

    private final DefaultInternalConstraintValidatorFactory delegate;
    private final Map<Class<?>, ConstraintValidatorEntry> validators = new ConcurrentHashMap<>();

    /**
     * Creates a reflection fallback constraint validator factory.
     *
     * @param beanContext The bean context
     */
    @Inject
    public ReflectionConstraintValidatorFactory(BeanContext beanContext) {
        this(new DefaultInternalConstraintValidatorFactory(beanContext));
    }

    /**
     * Creates a factory with an explicit delegate for package-local tests.
     *
     * @param delegate The generated metadata-aware factory to try before
     * reflective instantiation
     */
    ReflectionConstraintValidatorFactory(DefaultInternalConstraintValidatorFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> type) {
        ConstraintValidatorEntry entry = findConstraintValidator(type);
        return (T) entry.constraintValidator;
    }

    @Override
    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> type, Class<?> targetType, ConstraintTarget constraintTarget) {
        ConstraintValidatorEntry entry = findConstraintValidator(type);
        Class<?> resolvedTargetType = targetType.isPrimitive() ? ReflectionUtils.getWrapperType(targetType) : targetType;
        if (allowsConstraintTarget(entry.target, constraintTarget) && entry.targetType.isAssignableFrom(resolvedTargetType)) {
            return (T) entry.constraintValidator;
        }
        return null;
    }

    @Override
    public void releaseInstance(ConstraintValidator<?, ?> constraintValidator) {
        delegate.releaseInstance(constraintValidator);
    }

    private <T extends ConstraintValidator<?, ?>> ConstraintValidatorEntry findConstraintValidator(Class<T> type) {
        ConstraintValidatorEntry entry = validators.get(type);
        if (entry != null) {
            return entry;
        }
        ConstraintValidatorEntry created = instantiateWithDelegate(type);
        if (created == null) {
            created = instantiateReflectively(type);
        }
        validators.put(type, created);
        return created;
    }

    @Nullable
    private <T extends ConstraintValidator<?, ?>> ConstraintValidatorEntry instantiateWithDelegate(Class<T> type) {
        try {
            T constraintValidator = delegate.getInstance(type);
            if (constraintValidator == null) {
                return null;
            }
            return new ConstraintValidatorEntry(constraintValidator, getTargetType(type), getValidationTarget(type));
        } catch (ValidationException e) {
            return null;
        }
    }

    private <T extends ConstraintValidator<?, ?>> ConstraintValidatorEntry instantiateReflectively(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return new ConstraintValidatorEntry(constructor.newInstance(), getTargetType(type), getValidationTarget(type));
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot initialize validator: " + type.getName(), e);
        }
    }

    private static Class<?> getTargetType(Class<?> type) {
        Class<?> targetType = findConstraintValidatorTargetType(type);
        return targetType == null ? Object.class : targetType;
    }

    @Nullable
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

    @Nullable
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

    @Nullable
    private static Class<?> constraintValidatorTargetType(ParameterizedType parameterizedType) {
        Type rawType = parameterizedType.getRawType();
        if (rawType == io.micronaut.validation.validator.constraints.ConstraintValidator.class || rawType == ConstraintValidator.class) {
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

    private static Set<ValidationTarget> getValidationTarget(Class<?> type) {
        SupportedValidationTarget supportedValidationTarget = type.getAnnotation(SupportedValidationTarget.class);
        return supportedValidationTarget == null ? Set.of() : Set.of(supportedValidationTarget.value());
    }

    private static boolean allowsConstraintTarget(Set<ValidationTarget> validationTarget, ConstraintTarget constraintTarget) {
        if (constraintTarget == ConstraintTarget.PARAMETERS && !validationTarget.contains(ValidationTarget.PARAMETERS)) {
            return false;
        }
        return constraintTarget == ConstraintTarget.PARAMETERS || (validationTarget.isEmpty() || validationTarget.contains(ValidationTarget.ANNOTATED_ELEMENT));
    }

    private record ConstraintValidatorEntry(
        ConstraintValidator<?, ?> constraintValidator,
        Class<?> targetType,
        Set<ValidationTarget> target
    ) {
    }
}
