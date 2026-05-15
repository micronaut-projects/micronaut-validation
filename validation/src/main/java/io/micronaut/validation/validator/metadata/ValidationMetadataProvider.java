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
package io.micronaut.validation.validator.metadata;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.order.Ordered;
import jakarta.validation.metadata.BeanDescriptor;

import java.util.Optional;

/**
 * Optional provider of Jakarta Validation bean metadata.
 *
 * @since 5.1
 */
@Internal
public interface ValidationMetadataProvider extends Ordered {

    /**
     * @param beanType The bean type
     * @return A bean descriptor if this provider has metadata for the type
     */
    Optional<BeanDescriptor> getConstraintsForClass(Class<?> beanType);
}
