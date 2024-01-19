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
import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.CollectionUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ValidationException;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default implementation of {@link InternalConstraintValidatorFactory}.
 *
 * @author Denis Stepanov
 * @since 4.3.0
 */
@Singleton
@Internal
public class DefaultInternalConstraintValidatorFactory implements InternalConstraintValidatorFactory {

    private final Map<Class<?>, ConstraintValidatorEntry> validators = new ConcurrentHashMap<>();
    private final BeanIntrospector beanIntrospector;
    @Nullable
    private final BeanContext beanContext;

    public DefaultInternalConstraintValidatorFactory(BeanIntrospector beanIntrospector, @Nullable BeanContext beanContext) {
        this.beanIntrospector = beanIntrospector;
        this.beanContext = beanContext;
    }

    @Inject
    public DefaultInternalConstraintValidatorFactory(BeanContext beanContext) {
        this.beanIntrospector = BeanIntrospector.SHARED;
        this.beanContext = beanContext;
    }

    @Override
    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> type) {
        ConstraintValidatorEntry entry = findConstraintValidator(type);
        if (entry == null) {
            return null;
        }
        return (T) entry.constraintValidator;
    }

    @Override
    public void releaseInstance(ConstraintValidator<?, ?> constraintValidator) {
        validators.values()
            .stream()
            .filter(entry -> entry.beanRegistration != null && entry.constraintValidator == constraintValidator)
            .forEach(entry -> beanContext.destroyBean(entry.beanRegistration));
    }

    @Override
    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> type, Class<?> targetType, ConstraintTarget constraintTarget) {
        ConstraintValidatorEntry entry = findConstraintValidator(type);
        if (entry == null) {
            return null;
        }
        Class<?> resolvedTargetType = targetType.isPrimitive()
                ? ReflectionUtils.getWrapperType(targetType)
                : targetType;
        if (allowsConstraintTarget(entry.target, constraintTarget) && entry.targetType.isAssignableFrom(resolvedTargetType)) {
            return (T) entry.constraintValidator;
        }
        return null;
    }

    @Nullable
    private <T extends ConstraintValidator<?, ?>> ConstraintValidatorEntry findConstraintValidator(Class<T> type) {
        ConstraintValidatorEntry entry = validators.get(type);
        if (entry != null) {
            return entry;
        }
        try {
            entry = beanIntrospector.findIntrospection(type)
                    .map(this::instantiateConstraintValidatorEntry)
                    .orElseGet(() -> instantiateConstraintValidatorEntryOfBeanRegistration(type));
        } catch (Exception e) {
            throw new ValidationException("Cannot initialize validator: " + type.getName());
        }
        if (entry != null) {
            validators.put(type, entry);
        }
        return entry;
    }

    @NonNull
    private <T extends ConstraintValidator<?, ?>> ConstraintValidatorEntry instantiateConstraintValidatorEntry(@NonNull BeanIntrospection<T> beanIntrospection) {
        return new ConstraintValidatorEntry(beanIntrospection.instantiate(), getBeanType(beanIntrospection), getValidationTarget(beanIntrospection), null);
    }

    @Nullable
    private <T extends ConstraintValidator<?, ?>> ConstraintValidatorEntry instantiateConstraintValidatorEntryOfBeanRegistration(Class<T> type) {
        Collection<BeanRegistration<T>> beanRegistrations = beanContext.getBeanRegistrations(type);
        if (CollectionUtils.isEmpty(beanRegistrations)) {
            return null;
        }
        BeanRegistration<T> beanRegistration = beanRegistrations.iterator().next();
        List<Argument<?>> typeArguments = beanRegistration.getBeanDefinition().getTypeArguments(ConstraintValidator.class);
        return new ConstraintValidatorEntry(
                beanRegistration.bean(),
                typeArguments.size() == 2 ? typeArguments.get(1).getType() : Object.class,
                getValidationTarget(beanRegistration.getAnnotationMetadata()),
                beanRegistration);
    }

    private Class<?> getBeanType(BeanIntrospection<?> beanIntrospection) {
        AnnotatedType[] annotatedInterfaces = beanIntrospection.getBeanType().getAnnotatedInterfaces();
        if (annotatedInterfaces != null) {
            for (AnnotatedType annotatedInterface : annotatedInterfaces) {
                Type type = annotatedInterface.getType();
                if (type instanceof ParameterizedType parameterizedType && (
                    parameterizedType.getRawType() == io.micronaut.validation.validator.constraints.ConstraintValidator.class
                        || parameterizedType.getRawType() == ConstraintValidator.class
                )) {
                    Type[] typeArguments = parameterizedType.getActualTypeArguments();
                    if (typeArguments.length == 2) {
                        Type typeArgument = typeArguments[1];
                        if (typeArgument instanceof Class<?> aClass) {
                            return aClass;
                        }
                    }
                }
            }
        }
        return Object.class;
    }

    private Set<ValidationTarget> getValidationTarget(AnnotationMetadata annotationMetadata) {
        return Set.of(annotationMetadata.enumValues(SupportedValidationTarget.class, ValidationTarget.class));
    }

    private boolean allowsConstraintTarget(Set<ValidationTarget> validationTarget, ConstraintTarget constraintTarget) {
        if (constraintTarget == ConstraintTarget.PARAMETERS && !validationTarget.contains(ValidationTarget.PARAMETERS)) {
            return false;
        }
        if (constraintTarget != ConstraintTarget.PARAMETERS && (!validationTarget.isEmpty() && !validationTarget.contains(ValidationTarget.ANNOTATED_ELEMENT))) {
            return false;
        }
        return true;
    }

    private record ConstraintValidatorEntry(ConstraintValidator<?, ?> constraintValidator,
                                            Class<?> targetType,
                                            Set<ValidationTarget> target,
                                            @Nullable BeanRegistration<?> beanRegistration) {
    }

}
