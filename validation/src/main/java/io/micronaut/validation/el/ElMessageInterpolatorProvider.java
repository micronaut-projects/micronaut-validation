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
package io.micronaut.validation.el;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.validation.validator.messages.DefaultMessages;
import io.micronaut.validation.validator.metadata.MessageInterpolatorProvider;

import jakarta.validation.MessageInterpolator;

import java.util.Optional;

/**
 * Service adapter that checks for the optional Jakarta EL runtime.
 *
 * @since 5.3.0
 */
@Internal
public final class ElMessageInterpolatorProvider implements MessageInterpolatorProvider {
    /** Constructor for service discovery. */
    public ElMessageInterpolatorProvider() { }

    @Override
    public Optional<MessageInterpolator> create(ClassLoader classLoader) {
        if (!ClassUtils.isPresent("jakarta.el.ExpressionFactory", classLoader)) {
            return Optional.empty();
        }
        return Optional.of(new ElMessageInterpolator(new DefaultMessages(), null));
    }
}
