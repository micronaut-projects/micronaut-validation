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
import jakarta.validation.metadata.ConstraintDescriptor;

import java.util.Map;

/**
 * The attributes a message interpolator substitutes.
 *
 * <p>{@link ConstraintDescriptor#getAttributes()} returns typed values: an enum or nested annotation member of
 * a custom constraint needs the reflection module to become one. A message only renders the value, so where
 * the typed value cannot be had the interpolator reads the value the metadata holds - the constant's name -
 * instead of failing the validation that produced the violation.
 *
 * @author Denis Stepanov
 * @since 5.3.0
 */
@Internal
public final class MessageAttributes {

    private MessageAttributes() {
    }

    /**
     * @param descriptor The descriptor of the violated constraint
     * @return The attributes a message interpolates
     */
    public static Map<String, Object> of(ConstraintDescriptor<?> descriptor) {
        if (descriptor instanceof Source source) {
            return source.messageAttributes();
        }
        return descriptor.getAttributes();
    }

    /**
     * A descriptor answering the attributes of its messages.
     */
    interface Source {
        /**
         * @return The attributes a message interpolates
         */
        Map<String, Object> messageAttributes();
    }
}
