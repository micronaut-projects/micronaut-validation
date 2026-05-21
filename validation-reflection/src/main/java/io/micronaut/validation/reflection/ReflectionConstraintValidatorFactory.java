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
import io.micronaut.core.util.StringUtils;
import io.micronaut.validation.validator.constraints.ConstraintValidatorTargetResolver;
import io.micronaut.validation.validator.constraints.DefaultInternalConstraintValidatorFactory;
import io.micronaut.validation.validator.constraints.InternalConstraintValidatorFactory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ValidationException;
import jakarta.validation.constraintvalidation.ValidationTarget;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Constructor;
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
        Class<?> resolvedTargetType = ConstraintValidatorTargetResolver.resolveTargetType(targetType);
        if (ConstraintValidatorTargetResolver.allowsConstraintTarget(entry.target, constraintTarget) && entry.targetType.isAssignableFrom(resolvedTargetType)) {
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
            return new ConstraintValidatorEntry(
                constraintValidator,
                ConstraintValidatorTargetResolver.getTargetType(type),
                ConstraintValidatorTargetResolver.validationTargets(type)
            );
        } catch (ValidationException e) {
            return null;
        }
    }

    private <T extends ConstraintValidator<?, ?>> ConstraintValidatorEntry instantiateReflectively(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return new ConstraintValidatorEntry(
                constructor.newInstance(),
                ConstraintValidatorTargetResolver.getTargetType(type),
                ConstraintValidatorTargetResolver.validationTargets(type)
            );
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot initialize validator: " + type.getName(), e);
        }
    }

    private record ConstraintValidatorEntry(
        ConstraintValidator<?, ?> constraintValidator,
        Class<?> targetType,
        Set<ValidationTarget> target
    ) {
    }
}
