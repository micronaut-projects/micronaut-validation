/*
 * Copyright 2017-2020 original authors
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
import io.micronaut.context.Qualifier;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.clhm.ConcurrentLinkedHashMap;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.inject.qualifiers.TypeArgumentQualifier;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A factory bean that contains implementation for many of the default validations.
 * This approach is preferred as it generates fewer classes and smaller byte code than defining a
 * validator class for each case.
 *
 * @author graemerocher
 * @since 1.2
 */
@Singleton
@Introspected
public class DefaultConstraintValidators implements ConstraintValidatorRegistry {

    private final Map<DefaultConstraintValidators.ValidatorKey, ConstraintValidator<?, ?>> validatorCache = new ConcurrentLinkedHashMap.
            Builder<DefaultConstraintValidators.ValidatorKey, ConstraintValidator<?, ?>>().initialCapacity(10).maximumWeightedCapacity(40).build();

    @Nullable
    private final BeanContext beanContext;
    private final Map<ValidatorKey, ConstraintValidator<?, ?>> internalValidators;

    /**
     * Default constructor.
     */
    public DefaultConstraintValidators() {
        this(null);
    }

    /**
     * Constructor used for DI.
     *
     * @param beanContext The bean context
     */
    @Inject
    public DefaultConstraintValidators(@Nullable BeanContext beanContext) {
        this.beanContext = beanContext;
        List<Map.Entry<Argument<Object>, ConstraintValidator<?, ?>>> constraintValidators = InternalConstraintValidators.getConstraintValidators();
        Map<ValidatorKey, ConstraintValidator<?, ?>> validatorMap = CollectionUtils.newHashMap(constraintValidators.size());
        for (Map.Entry<Argument<Object>, ConstraintValidator<?, ?>> entry : constraintValidators) {
            Argument<Object> definition = entry.getKey();
            ConstraintValidator<?, ?> constraintValidator = entry.getValue();
            final Argument<?>[] typeParameters = definition.getTypeParameters();
            if (ArrayUtils.isEmpty(typeParameters)) {
                continue;
            }

            final int len = typeParameters.length;
            if (len == 2) {
                final Class<?> targetType = ReflectionUtils.getWrapperType(typeParameters[1].getType());
                final ValidatorKey key = new ValidatorKey(typeParameters[0].getType(), targetType);
                validatorMap.put(key, constraintValidator);
            } else if (len == 1) {
                Class<?> type = typeParameters[0].getType();
                if (constraintValidator instanceof SizeValidator) {
                    final ValidatorKey key = new ValidatorKey(Size.class, type);
                    validatorMap.put(key, constraintValidator);
                } else if (constraintValidator instanceof DigitsValidator) {
                    final ValidatorKey key = new ValidatorKey(Digits.class, type);
                    validatorMap.put(key, constraintValidator);
                } else if (constraintValidator instanceof DecimalMaxValidator) {
                    final ValidatorKey key = new ValidatorKey(DecimalMax.class, type);
                    validatorMap.put(key, constraintValidator);
                } else if (constraintValidator instanceof DecimalMinValidator) {
                    final ValidatorKey key = new ValidatorKey(DecimalMin.class, type);
                    validatorMap.put(key, constraintValidator);
                }
            }
        }
        validatorMap.put(
                new ValidatorKey(Pattern.class, CharSequence.class),
                new PatternValidator()
        );
        validatorMap.put(
                new ValidatorKey(Email.class, CharSequence.class),
                new EmailValidator()
        );
        this.internalValidators = validatorMap;
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public <A extends Annotation, T> Optional<ConstraintValidator<A, T>> findConstraintValidator(@NonNull Class<A> constraintType, @NonNull Class<T> targetType) {
        ArgumentUtils.requireNonNull("constraintType", constraintType);
        ArgumentUtils.requireNonNull("targetType", targetType);
        final var key = new ValidatorKey(constraintType, targetType);
        targetType = (Class<T>) ReflectionUtils.getWrapperType(targetType);

        ConstraintValidator<?, ?> constraintValidator = internalValidators.get(key);
        if (constraintValidator != null) {
            return Optional.of((ConstraintValidator<A, T>) constraintValidator);
        }

        constraintValidator = validatorCache.get(key);
        if (constraintValidator != null) {
            return Optional.of((ConstraintValidator<A, T>) constraintValidator);
        }

        Optional<ConstraintValidator<A, T>> local = findInternalConstraintValidator(constraintType, targetType);
        if (local.isPresent()) {
            validatorCache.put(key, local.get());
            return local;
        }

        if (beanContext != null) {
            Argument<ConstraintValidator<A, T>> argument = (Argument) Argument.of(ConstraintValidator.class);
            final Qualifier<ConstraintValidator<A, T>> qualifier = Qualifiers.byTypeArguments(
                    constraintType,
                    targetType
            );
            Optional<ConstraintValidator<A, T>> bean = beanContext.findBean(argument, qualifier);
            if (bean.isPresent()) {
                validatorCache.put(key, bean.get());
                return bean;
            }

        } else {
            // last chance lookup
            Optional<ConstraintValidator<A, T>> cv = findLocalConstraintValidator(constraintType, targetType);
            if (cv.isPresent()) {
                validatorCache.put(key, cv.get());
                return cv;
            }
        }

        validatorCache.put(key, ConstraintValidator.VALID);

        return Optional.empty();
    }

    private <A extends Annotation, T> Optional<ConstraintValidator<A, T>> findInternalConstraintValidator(Class<A> constraintType,
                                                                                                          Class<T> validationType) {
        final Class<?>[] finalTypeArguments = {constraintType, validationType};
        return internalValidators.entrySet().stream()
                .filter(entry -> {
                            final ValidatorKey k = entry.getKey();
                            return TypeArgumentQualifier.areTypesCompatible(finalTypeArguments, Arrays.asList(k.constraintType, k.targetType));
                        }
                ).map(e -> (ConstraintValidator<A, T>) e.getValue())
                .findFirst();
    }

    /**
     * Last chance resolve for constraint validator.
     *
     * @param constraintType The constraint type
     * @param targetType     The target type
     * @param <A>            The annotation type
     * @param <T>            The target type
     * @return The validator if present
     */
    protected <A extends Annotation, T> Optional<ConstraintValidator<A, T>> findLocalConstraintValidator(@NonNull Class<A> constraintType,
                                                                                                         @NonNull Class<T> targetType) {
        return Optional.empty();
    }

    /**
     * Key for caching validators.
     *
     * @param constraintType The constraint type
     * @param targetType     The target type
     */
    public record ValidatorKey(@NonNull Class<?> constraintType,
                               @NonNull Class<?> targetType) {
    }

}
