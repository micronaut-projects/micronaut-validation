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
package io.micronaut.validation.validator;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;

import java.lang.reflect.Type;

/**
 * The structure of a generic signature the specification API hands over as a {@link Type}: the types and
 * their type arguments, read from the object the API supplies and from nothing else. What a type binds in a
 * super type, and the annotations a signature carries, mean reading the class, which is what
 * {@link ReflectionSupport} decides and the reflection module does.
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
public final class GenericArguments {

    private GenericArguments() {
    }

    /**
     * The argument of a type: the class it erases to, with the type arguments it binds, an unbound variable
     * standing for its erasure.
     *
     * @param type The type
     * @return The argument
     */
    public static Argument<?> of(Type type) {
        return ReflectionSupport.get().argumentOf(type);
    }
}
