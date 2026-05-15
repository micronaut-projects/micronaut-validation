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

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.util.StringUtils;
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;
import jakarta.inject.Singleton;
import jakarta.validation.metadata.BeanDescriptor;

import java.util.Optional;

/**
 * Reflection metadata provider fallback.
 *
 * @since 5.1
 */
@Singleton
@Requires(property = ReflectionValidator.ENABLED, notEquals = StringUtils.FALSE, defaultValue = StringUtils.TRUE)
public final class ReflectionValidationMetadataProvider implements ValidationMetadataProvider {

    @Override
    public Optional<BeanDescriptor> getConstraintsForClass(Class<?> beanType) {
        return Optional.of(ReflectionValidator.ReflectionBeanMetadata.of(beanType));
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }
}
