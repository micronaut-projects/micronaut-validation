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
package io.micronaut.validation.el.processor;

import jakarta.validation.Constraint;
import jakarta.validation.OverridesAttribute;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A constraint composed of {@link Size}, whose message and whose composing message both carry an expression.
 *
 * <p>{@code max} overrides the {@code max} of the composing {@code @Size}, so the message of a violation of
 * {@code @Tiny(max = 5)} says 5, not 3.</p>
 */
@Documented
@Constraint(validatedBy = {})
@Size(min = 1, max = 3, message = "${validatedValue.strip()} is longer than {max}, unless overridden")
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Tiny {

    String message() default "${validatedValue.concat(' is not tiny')}";

    @OverridesAttribute(constraint = Size.class, name = "max")
    int max() default 3;

    /**
     * Overrides the message of the composing {@code @Size}: the expression the processor compiles for it is
     * the one of this member, which proves the override is applied at compilation time too.
     */
    @OverridesAttribute(constraint = Size.class, name = "message")
    String sizeMessage() default "${validatedValue.toUpperCase()} is longer than {max}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
