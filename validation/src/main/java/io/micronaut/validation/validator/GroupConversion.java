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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import org.jspecify.annotations.Nullable;

/**
 * A {@link ConvertGroup} as declared.
 *
 * @param from The source group, the default group when not declared
 * @param to The target group, null when not declared
 * @since 5.3.0
 */
@Internal
record GroupConversion(Class<?> from, @Nullable Class<?> to) {

    static GroupConversion of(AnnotationValue<ConvertGroup> conversion) {
        return new GroupConversion(conversion.classValue("from").orElse(Default.class), conversion.classValue("to").orElse(null));
    }

    /**
     * @return The target group
     * @throws ConstraintDeclarationException when none is declared
     */
    Class<?> requireTo() {
        if (to == null) {
            throw new ConstraintDeclarationException("Group conversion is missing a target group");
        }
        return to;
    }
}
