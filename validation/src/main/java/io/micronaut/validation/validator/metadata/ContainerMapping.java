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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Maps an inherited container's variables to the declaring container's variables.
 *
 * @since 5.3.0
 */
@Internal
@Retention(RetentionPolicy.RUNTIME)
public @interface ContainerMapping {
    /**
     * Resolves a generated type reference.
     *
     * @return The inherited container name
     */
    String type();
    /**
     * Maps inherited container variables to their declaration indexes.
     *
     * @return The variable indexes, or -1 for a bound type
     */
    int[] indexes();
}
