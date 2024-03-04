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
package io.micronaut.validation.validator;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.beans.BeanProperty;
import java.util.Arrays;
import java.util.List;

/**
 * Context object to allow configuring validation behaviour.
 */
public interface BeanValidationContext {
    /**
     * The default validation context.
     */
    BeanValidationContext DEFAULT = new DefaultBeanValidationContext(List.of());

    /**
     * The validation groups.
     * @return The groups
     */
    default List<Class<?>> groups() {
        return List.of();
    }

    /**
     * Create a validation context from the given groups.
     * @param groups The groups
     * @return The context
     */
    static @NonNull BeanValidationContext fromGroups(Class<?>... groups) {
        return new DefaultBeanValidationContext(
            groups != null ? Arrays.asList(groups) : List.of()
        );
    }

    /**
     * Hook to allow exclusion of properties during validation.
     * @param object The object being validated
     * @param property The property being validated.
     * @return True if it should be validated.
     * @param <T> The object type
     */
    default <T> boolean isPropertyValidated(
        @NonNull T object, @NonNull BeanProperty<T, Object> property) {
        return true;
    }
}
