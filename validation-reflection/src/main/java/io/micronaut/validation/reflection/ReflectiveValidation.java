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

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.util.StringUtils;
import io.micronaut.reflection.ReflectionBeanIntrospector;

import java.util.Set;

/**
 * The introspector describing reflectively the types without a generated introspection, for a validator that
 * has to describe every type it is handed - the Jakarta Validation bootstrap, which validates the types of an
 * application that never ran the annotation processors.
 *
 * @author Denis Stepanov
 * @since 5.2
 */
public final class ReflectiveValidation {

    /**
     * The system property switching the reflective description of the types off: {@code false} leaves the
     * generated introspections alone, which is how the introspection profile of the TCK runs.
     */
    public static final String ENABLED = "micronaut.validation.reflection.enabled";

    private ReflectiveValidation() {
    }

    /**
     * @return Whether the types without a generated introspection are described reflectively
     */
    public static boolean isEnabled() {
        return !StringUtils.FALSE.equalsIgnoreCase(System.getProperty(ENABLED, StringUtils.TRUE));
    }

    /**
     * Whether an introspector already describes the types reflectively.
     *
     * @param beanIntrospector The introspector
     * @return Whether it supplements the generated introspections
     */
    public static boolean isSupplemented(BeanIntrospector beanIntrospector) {
        return beanIntrospector instanceof ReflectionBeanIntrospector;
    }

    /**
     * The generated introspections, supplemented by the reflection module of micronaut-core for the types
     * without one, unless {@link #ENABLED} says otherwise. Every type is described, whatever the policy of the
     * module allows: the validator is handed the types to validate, it does not choose them.
     *
     * @param beanIntrospector The introspector of the generated introspections
     * @return The supplemented introspector, or the given one when reflection is switched off
     */
    public static BeanIntrospector supplemented(BeanIntrospector beanIntrospector) {
        if (!isEnabled() || isSupplemented(beanIntrospector)) {
            return beanIntrospector;
        }
        // Jakarta Validation reads a field directly, and a type described reflectively carries no @Introspected
        // to declare that, so the access kinds are asked for here
        return new ReflectionBeanIntrospector(beanIntrospector, type -> true, true,
            Set.of(Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD));
    }
}
