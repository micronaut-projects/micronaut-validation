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

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospection;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstructorCrossParameterTest {

    @Test
    void introspectionOverloadRunsTheConstructorCrossParameterConstraint() {
        try (var factory = new DefaultValidatorFactory()) {
            var validator = (Validator) factory.getValidator();
            var introspection = BeanIntrospection.getIntrospection(Range.class);
            assertEquals(1, validator.forExecutables().validateConstructorParameters(introspection, new Object[]{2, 1}).size());
            assertEquals(0, validator.forExecutables().validateConstructorParameters(introspection, new Object[]{1, 2}).size());
        }
    }

    @Test
    void argumentsAloneDoNotHideTheConstraintFromLaterCalls() throws Exception {
        try (var factory = new DefaultValidatorFactory()) {
            var validator = (Validator) factory.getValidator();
            var introspection = BeanIntrospection.getIntrospection(Range.class);
            validator.forExecutables().validateConstructorParameters(Range.class, introspection.getConstructorArguments(), new Object[]{2, 1}, new Class<?>[0]);
            var constructor = Range.class.getConstructor(int.class, int.class);
            assertEquals(1, validator.forExecutables().validateConstructorParameters(constructor, new Object[]{2, 1}).size());
        }
    }

    @Introspected
    public static class Range {
        private final int min;
        private final int max;

        @Ordered
        public Range(int min, int max) {
            this.min = min;
            this.max = max;
        }

        public int getMin() {
            return min;
        }

        public int getMax() {
            return max;
        }
    }

    @Target(ElementType.CONSTRUCTOR)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = OrderedValidator.class)
    public @interface Ordered {
        String message() default "out of order";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @Introspected
    @SupportedValidationTarget(ValidationTarget.PARAMETERS)
    public static class OrderedValidator implements ConstraintValidator<Ordered, Object[]> {
        @Override
        public boolean isValid(Object[] value, ConstraintValidatorContext context) {
            return (Integer) value[0] <= (Integer) value[1];
        }
    }
}
