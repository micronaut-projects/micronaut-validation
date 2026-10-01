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

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.DelegatingBeanDefinition;
import io.micronaut.inject.DisposableBeanDefinition;
import io.micronaut.inject.InstantiatableBeanDefinition;

import jakarta.validation.ValidationException;

/**
 * Treats a validator definition as an owned prototype without changing the application's
 * definition. The context retains dependent registrations and applies its normal creation and
 * destruction hooks.
 *
 * @param <T> The validator type
 */
@Internal
final class OwnedValidatorDefinition<T>
        implements DelegatingBeanDefinition<T>,
                InstantiatableBeanDefinition<T>,
                DisposableBeanDefinition<T> {
    private final BeanDefinition<T> target;

    OwnedValidatorDefinition(BeanDefinition<T> target) {
        this.target = target;
    }

    @Override
    public BeanDefinition<T> getTarget() {
        return target;
    }

    @Override
    public boolean isSingleton() {
        return false;
    }

    @Override
    public T instantiate(BeanResolutionContext resolutionContext, BeanContext context) {
        if (target instanceof InstantiatableBeanDefinition<T> instantiatable) {
            return instantiatable.instantiate(resolutionContext, context);
        }
        throw new ValidationException(
                "Validator definition cannot instantiate " + target.getName());
    }

    @Override
    public T dispose(BeanResolutionContext resolutionContext, BeanContext context, T bean) {
        return target instanceof DisposableBeanDefinition<T> disposable
                ? disposable.dispose(resolutionContext, context, bean)
                : bean;
    }
}
