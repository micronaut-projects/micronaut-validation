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
import io.micronaut.core.beans.BeanProperty;

/**
 * A property whose members are validated apart although its introspection merges the declarations of its
 * properties: a field and a getter each configured on their own constrain the value each reads, so the
 * validator walks the members of the property, see {@link BeanProperty#getMembers()}, instead of the
 * property itself.
 *
 * @since 5.3.0
 */
@Internal
public interface SeparatedPropertyDeclarations {
}
