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
import io.micronaut.core.beans.BeanIntrospector;

/**
 * An introspector describing some types by decorating the introspections of another: an XML constraint mapping
 * adds the annotation metadata it declares to the introspection of the bean it names. The validator reads the
 * decorated introspections like any other, and asks for the introspector underneath to tell where they come
 * from.
 *
 * @since 5.3.0
 */
@Internal
public interface BeanIntrospectorDecorator extends BeanIntrospector {

    /**
     * @return The introspector whose introspections this one decorates
     */
    BeanIntrospector getDecorated();
}
