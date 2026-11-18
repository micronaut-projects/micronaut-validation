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
package io.micronaut.validation.el;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.validator.Validator;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The template of a violation a validator builds may carry what it validated: expressions in it are not
 * evaluated unless configured, its variables are.
 */
class CustomViolationElTest {

    @Test
    void expressionsOfACustomViolationAreNotEvaluatedByDefault() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("${1 + 1} abc", message(context));
        }
    }

    @Test
    void expressionsOfACustomViolationAreEvaluatedWhenEnabled() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("micronaut.validator.custom-violation-expressions", true))) {
            assertEquals("2 abc", message(context));
        }
    }

    @Test
    void expressionsOfAConstraintMessageAreEvaluated() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("3", context.getBean(Validator.class).validate(new Messaged()).iterator().next().getMessage());
        }
    }

    private static String message(ApplicationContext context) {
        return context.getBean(Validator.class).validate(new Checked()).iterator().next().getMessage();
    }

    @Constraint(validatedBy = CustomTemplateValidator.class)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface CustomTemplate {
        String message() default "default";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static final class CustomTemplateValidator implements ConstraintValidator<CustomTemplate, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("${1 + 1} ${validatedValue}").addConstraintViolation();
            return false;
        }
    }

    @Introspected
    public static class Checked {
        @CustomTemplate
        public String value = "abc";

        public String getValue() {
            return value;
        }
    }

    @Introspected
    public static class Messaged {
        @jakarta.validation.constraints.NotNull(message = "${1 + 2}")
        public String value;

        public String getValue() {
            return value;
        }
    }
}
