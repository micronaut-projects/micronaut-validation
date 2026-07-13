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
package io.micronaut.validation.annotation;

import io.micronaut.validation.validator.constraints.URLValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Constraint that validates that a character sequence is a URL.
 *
 * @since 5.1.0
 */
@Documented
@Constraint(validatedBy = URLValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(URL.List.class)
public @interface URL {

    /**
     * @return The error message.
     */
    String message() default "must be a valid URL";

    /**
     * @return The groups to apply.
     */
    Class<?>[] groups() default {};

    /**
     * @return The payload.
     */
    Class<? extends Payload>[] payload() default {};

    /**
     * @return The required URL scheme, or an empty string to allow any scheme.
     */
    String protocol() default "";

    /**
     * @return The required URL host, or an empty string to allow any host.
     */
    String host() default "";

    /**
     * @return The required URL port, or {@code -1} to allow any port.
     */
    int port() default -1;

    /**
     * @return An additional regular expression the URL must match.
     */
    String regexp() default ".*";

    /**
     * @return The flags used with {@link #regexp()}.
     */
    Pattern.Flag[] flags() default {};

    /**
     * Defines several {@link URL} annotations on the same element.
     *
     * @since 5.1.0
     */
    @Documented
    @Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {

        /**
         * @return The URL constraints.
         */
        URL[] value();
    }
}
