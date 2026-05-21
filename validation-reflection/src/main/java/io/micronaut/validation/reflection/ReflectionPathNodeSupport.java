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

import jakarta.validation.Path;
import org.jspecify.annotations.Nullable;

/**
 * Shared defaults for reflection fallback {@link Path.Node} implementations
 * that are not reached through iterable or container traversal.
 *
 * <p>This exists to keep the many Jakarta path-node adapters in the reflection
 * module consistent without exposing them as user-facing API.</p>
 *
 * @since 5.1
 */
interface ReflectionPlainPathNode extends Path.Node {

    @Override
    default boolean isInIterable() {
        return false;
    }

    @Override
    @Nullable
    default Integer getIndex() {
        return null;
    }

    @Override
    @Nullable
    default Object getKey() {
        return null;
    }

    @Override
    default <T extends Path.Node> T as(Class<T> nodeType) {
        return nodeType.cast(this);
    }
}

/**
 * Shared defaults for plain bean nodes that do not carry container context.
 *
 * @since 5.1
 */
interface ReflectionPlainBeanPathNode extends Path.BeanNode, ReflectionPlainPathNode {

    @Override
    @Nullable
    default String getName() {
        return null;
    }

    @Override
    @Nullable
    default Class<?> getContainerClass() {
        return null;
    }

    @Override
    @Nullable
    default Integer getTypeArgumentIndex() {
        return null;
    }
}
