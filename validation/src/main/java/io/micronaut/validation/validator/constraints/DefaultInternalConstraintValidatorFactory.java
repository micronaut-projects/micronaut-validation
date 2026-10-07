/*
 * Copyright 2017-2024 original authors
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

import io.micronaut.context.BeanContext;
import io.micronaut.context.scope.CreatedBean;
import io.micronaut.context.annotation.Bean;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.type.Argument;
import io.micronaut.validation.validator.ReflectionSupport;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ValidationException;
import jakarta.validation.constraintvalidation.ValidationTarget;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The default implementation of {@link InternalConstraintValidatorFactory}.
 *
 * @author Denis Stepanov
 * @since 4.3.0
 */
@Bean(typed = InternalConstraintValidatorFactory.class)
@Singleton
@Internal
public class DefaultInternalConstraintValidatorFactory implements InternalConstraintValidatorFactory {

    private final Map<ConstraintValidator<?, ?>, ConstraintValidatorEntry> validators =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final BeanIntrospector beanIntrospector;
    private final ReflectionSupport reflectionSupport;
    @Nullable
    private final BeanContext beanContext;

    /**
     * Creates a factory using explicit generated metadata and optional dependency injection.
     * @param beanIntrospector The metadata resolver
     * @param beanContext The optional context owning validator dependencies
     */
    public DefaultInternalConstraintValidatorFactory(BeanIntrospector beanIntrospector, @Nullable BeanContext beanContext) {
        this(beanIntrospector, beanContext, ReflectionSupport.get());
    }

    /**
     * Creates a factory with application-scoped access capabilities.
     * @param beanIntrospector The configured introspector
     * @param beanContext The optional bean context
     * @param reflectionSupport The configured access provider
     */
    public DefaultInternalConstraintValidatorFactory(BeanIntrospector beanIntrospector,
            @Nullable BeanContext beanContext, ReflectionSupport reflectionSupport) {
        this.beanIntrospector = beanIntrospector;
        this.beanContext = beanContext;
        this.reflectionSupport = reflectionSupport.withIntrospector(beanIntrospector);
    }

    /**
     * Creates the context-managed validator factory.
     * @param beanContext The context owning validator dependencies
     */
    @Inject
    public DefaultInternalConstraintValidatorFactory(BeanContext beanContext) {
        this(BeanIntrospector.forClassLoader(beanContext.getClassLoader()), beanContext,
            ReflectionSupport.forClassLoader(beanContext.getClassLoader()));
    }

    @Override
    public <T extends ConstraintValidator<?, ?>> @Nullable T getInstance(Class<T> type) {
        ConstraintValidatorEntry entry = findConstraintValidator(type);
        if (entry == null) {
            return null;
        }
        return (T) entry.constraintValidator;
    }

    @Override
    public void releaseInstance(ConstraintValidator<?, ?> constraintValidator) {
        ConstraintValidatorEntry entry = validators.remove(constraintValidator);
        if (entry != null && entry.createdBean != null) {
            entry.createdBean.close();
        }
    }

    @Override
    public <T extends ConstraintValidator<?, ?>> @Nullable T getInstance(Class<T> type, Class<?> targetType, ConstraintTarget constraintTarget) {
        ConstraintValidatorEntry entry = findConstraintValidator(type);
        if (entry == null) {
            return null;
        }
        Class<?> resolvedTargetType = ConstraintValidatorTargetResolver.resolveTargetType(targetType);
        if (ConstraintValidatorTargetResolver.allowsConstraintTarget(entry.target, constraintTarget) && entry.targetType.isAssignableFrom(resolvedTargetType)) {
            return (T) entry.constraintValidator;
        }
        releaseInstance(entry.constraintValidator);
        return null;
    }

    @Nullable
    private <T extends ConstraintValidator<?, ?>> ConstraintValidatorEntry findConstraintValidator(Class<T> type) {
        ConstraintValidatorEntry entry;
        try {
            // Managed construction also supplies member injection, interceptors and lifecycle callbacks.
            entry = beanContext != null && beanContext.findBeanDefinition(type).isPresent()
                ? instantiateConstraintValidatorEntryOfBeanRegistration(type)
                : beanIntrospector.findIntrospection(type)
                    .filter(introspection -> introspection.getConstructorArguments().length == 0)
                    .map(this::instantiateConstraintValidatorEntry)
                    .orElseGet(() -> instantiateConstraintValidatorEntryOfDeclaredConstructor(type));
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException("Cannot initialize validator: " + type.getName(), e);
        }
        if (entry != null) {
            validators.put(entry.constraintValidator, entry);
        }
        return entry;
    }

    @Nullable
    private <T extends ConstraintValidator<?, ?>> ConstraintValidatorEntry instantiateConstraintValidatorEntryOfDeclaredConstructor(Class<T> type) {
        T constraintValidator = reflectionSupport.instantiate(type);
        if (constraintValidator == null) {
            return null;
        }
        return new ConstraintValidatorEntry(
            constraintValidator,
            ConstraintValidatorTargetResolver.getTargetType(reflectionSupport, type),
            ConstraintValidatorTargetResolver.validationTargets(reflectionSupport, type),
            null);
    }

    private <T extends ConstraintValidator<?, ?>> ConstraintValidatorEntry instantiateConstraintValidatorEntry(@NonNull BeanIntrospection<T> beanIntrospection) {
        return new ConstraintValidatorEntry(
            beanIntrospection.instantiate(),
            ConstraintValidatorTargetResolver.getTargetType(reflectionSupport, beanIntrospection),
            ConstraintValidatorTargetResolver.validationTargets(reflectionSupport, beanIntrospection),
            null);
    }

    @Nullable
    private <T extends ConstraintValidator<?, ?>> ConstraintValidatorEntry instantiateConstraintValidatorEntryOfBeanRegistration(Class<T> type) {
        if (beanContext == null || beanContext.findBeanDefinition(type).isEmpty()) {
            return instantiateConstraintValidatorEntryOfDeclaredConstructor(type);
        }
        var definition = beanContext.getBeanDefinition(type);
        List<Argument<?>> arguments = definition.getTypeArguments(ConstraintValidator.class);
        // a validator instance belongs to the validator that initialized it, whatever the scope of its bean: the
        // context creates a fresh one with its own dependencies, which closing the registration releases
        CreatedBean<T> registration = beanContext.createBeanRegistration(definition);
        T instance = registration.bean();
        return new ConstraintValidatorEntry(
                instance,
                arguments.size() == 2 ? arguments.get(1).getType() : Object.class,
                ConstraintValidatorTargetResolver.validationTargets(reflectionSupport,
                        definition.getAnnotationMetadata()),
                registration);
    }

    private record ConstraintValidatorEntry(ConstraintValidator<?, ?> constraintValidator,
                                            Class<?> targetType,
                                            Set<ValidationTarget> target,
                                            @Nullable CreatedBean<?> createdBean) { }
}
