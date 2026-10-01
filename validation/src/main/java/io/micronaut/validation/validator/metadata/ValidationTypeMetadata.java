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

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Generated hierarchy and group metadata.
 *
 * @param superType The superclass, or null
 * @param interfaces The direct interfaces
 * @param groupSequence The sequence, or null for an ordinary group
 * @since 5.3.0
 */
@Internal
public record ValidationTypeMetadata(
        @Nullable Class<?> superType,
        List<Class<?>> interfaces,
        @Nullable List<Class<?>> groupSequence) {
    public ValidationTypeMetadata {
        interfaces = List.copyOf(interfaces);
        if (groupSequence != null) {
            groupSequence = List.copyOf(groupSequence);
        }
    }
}
