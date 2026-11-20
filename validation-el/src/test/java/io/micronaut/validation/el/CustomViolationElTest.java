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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The template of a violation a validator builds may carry what it validated: unless configured, an expression
 * in it is evaluated only if it reads variables, while the text of a message bundle it names is evaluated in full.
 */
class CustomViolationElTest {

    @Test
    void onlyVariablesOfACustomViolationAreEvaluatedByDefault() {
        try (ApplicationContext context = ApplicationContext.run()) {
            // variables, operators and the formatter resolve, a method or property of a value does not
            assertEquals("2 abc", message(context));
            assertEquals("${validatedValue.toUpperCase()}", messageOf(context, new Upper()));
            assertEquals("<fmt>", messageOf(context, new Formatted()));
        }
    }

    @Test
    void whatAnExpressionReads() {
        assertTrue(ElMessageInterpolator.readsVariablesOnly("validatedValue"));
        assertTrue(ElMessageInterpolator.readsVariablesOnly("1.5 + .5 * value"));
        assertTrue(ElMessageInterpolator.readsVariablesOnly("inclusive ? 'or equal to' : 'a.b(c)[d]'"));
        assertTrue(ElMessageInterpolator.readsVariablesOnly("formatter.format('%s', not (a and b))"));
        assertFalse(ElMessageInterpolator.readsVariablesOnly("validatedValue.bytes"));
        assertFalse(ElMessageInterpolator.readsVariablesOnly("validatedValue['bytes']"));
        assertFalse(ElMessageInterpolator.readsVariablesOnly("validatedValue.toString()"));
        assertFalse(ElMessageInterpolator.readsVariablesOnly("formatter.formatX"));
        assertFalse(ElMessageInterpolator.readsVariablesOnly("formatter.format('%s', validatedValue.bytes)"));
        assertFalse(ElMessageInterpolator.readsVariablesOnly("fn(validatedValue)"));
    }

    @Test
    void bundleTextReusedByACustomViolationIsEvaluated() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertEquals("BUNDLE from bundle", messageOf(context, new Bundled()));
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
        return messageOf(context, new Checked());
    }

    private static String messageOf(ApplicationContext context, Object bean) {
        return context.getBean(Validator.class).validate(bean).iterator().next().getMessage();
    }

    @Constraint(validatedBy = CustomTemplateValidator.class)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface CustomTemplate {
        String message() default "default";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static final class CustomTemplateValidator implements ConstraintValidator<CustomTemplate, String> {
        // the template a value is reported with
        private static final Map<String, String> TEMPLATES = Map.of(
            "abc", "${1 + 1} ${validatedValue}",
            "upper", "${validatedValue.toUpperCase()}",
            "fmt", "${formatter.format('<%s>', validatedValue)}",
            "bundle", "{review.custom.upper}");

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(TEMPLATES.get(value)).addConstraintViolation();
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
    public static class Upper {
        @CustomTemplate
        public String value = "upper";

        public String getValue() {
            return value;
        }
    }

    @Introspected
    public static class Formatted {
        @CustomTemplate
        public String value = "fmt";

        public String getValue() {
            return value;
        }
    }

    @Introspected
    public static class Bundled {
        @CustomTemplate
        public String value = "bundle";

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
