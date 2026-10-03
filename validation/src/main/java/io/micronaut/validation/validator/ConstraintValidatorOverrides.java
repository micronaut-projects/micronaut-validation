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
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * The validators a configuration defines for a constraint in place of, or next to, the ones the
 * constraint declares: the {@code constraint-definition} of an XML mapping. A constraint belongs to
 * no bean, so this is the one part of a mapping the introspection of a bean cannot carry.
 *
 * @since 5.3.0
 */
@Internal
@FunctionalInterface
public interface ConstraintValidatorOverrides {

    /**
     * No constraint is redefined.
     */
    ConstraintValidatorOverrides NONE = (constraintType, declared) -> null;

    /**
     * @param constraintType The constraint
     * @param declared       The validators the occurrence of the constraint carries
     * @return The validators of the constraint as configured, or null when it is not redefined
     */
    @Nullable List<Class<?>> validatorsOf(Class<? extends Annotation> constraintType, List<Class<?>> declared);
}
