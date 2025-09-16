/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.validation.annotation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import io.micronaut.validation.validator.constraints.InEnumValidator;

/**
 * Constraint that validates that a String value is in the specified enum's constant names.
 */
@Documented
@Constraint(validatedBy = InEnumValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface InEnum {

    /**
     * @return The enum class to check against.
     */
    Class<? extends Enum<?>> value();

    /**
     * @return Whether the comparison is case-sensitive.
     */
    boolean caseSensitive() default true;

    /**
     * @return message code
     */
    String message() default "Not in the list of supported values ({validatedValue})";

    /**
     * @return The groups to apply
     */
    Class<?>[] groups() default {};

    /**
     * @return The payload
     */
    Class<? extends Payload>[] payload() default {};
}
