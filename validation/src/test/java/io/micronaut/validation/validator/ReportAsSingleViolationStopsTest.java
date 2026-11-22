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
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The evaluation of the constraints composing a constraint reported as a single violation stops on the first
 * failure, as the Javadoc of {@link ReportAsSingleViolation} requires: a later composing validator is not called.
 */
class ReportAsSingleViolationStopsTest {

    static final AtomicInteger CALLS = new AtomicInteger();

    @BeforeEach
    void reset() {
        CALLS.set(0);
    }

    @Test
    void theComposingConstraintsAfterTheFirstFailureAreNotEvaluated() {
        try (var factory = new DefaultValidatorFactory()) {
            Set<ConstraintViolation<Bean>> violations = factory.getValidator().validate(new Bean());
            assertEquals(1, violations.size());
            assertEquals("single", violations.iterator().next().getMessage());
            // the first composing validator fails: the second, which would throw, is never called
            assertEquals(1, CALLS.get());
        }
    }

    @Test
    void theComposingConstraintsAreNotEvaluatedWhenTheComposedValidatorFails() {
        try (var factory = new DefaultValidatorFactory()) {
            Set<ConstraintViolation<OwnBean>> violations = factory.getValidator().validate(new OwnBean());
            assertEquals(1, violations.size());
            assertEquals("own", violations.iterator().next().getMessage());
            assertEquals(0, CALLS.get());
        }
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static final class Bean {
        @Single public String value = "x";
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static final class OwnBean {
        @SingleWithOwnValidator public String value = "x";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
    @Constraint(validatedBy = {})
    @ReportAsSingleViolation
    @CountingFailure
    @Throwing
    @interface Single {
        String message() default "single";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
    @Constraint(validatedBy = FailingValidator.class)
    @ReportAsSingleViolation
    @CountingFailure
    @Throwing
    @interface SingleWithOwnValidator {
        String message() default "own";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
    @Constraint(validatedBy = CountingFailureValidator.class)
    @interface CountingFailure {
        String message() default "counting";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
    @Constraint(validatedBy = ThrowingValidator.class)
    @interface Throwing {
        String message() default "throwing";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static final class FailingValidator implements ConstraintValidator<SingleWithOwnValidator, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return false;
        }
    }

    public static final class CountingFailureValidator implements ConstraintValidator<CountingFailure, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            CALLS.incrementAndGet();
            return false;
        }
    }

    public static final class ThrowingValidator implements ConstraintValidator<Throwing, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            throw new IllegalStateException("not evaluated after a failure");
        }
    }
}
