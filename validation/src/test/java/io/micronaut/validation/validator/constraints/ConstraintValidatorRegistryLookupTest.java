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
package io.micronaut.validation.validator.constraints;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.AnnotationValue;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry answers a type no validator handles the same way every time, and a validator bean that is not a
 * singleton is looked up again rather than kept.
 */
class ConstraintValidatorRegistryLookupTest {

    @Test
    void aMissIsAnsweredTheSameWayEveryTime() {
        var registry = new DefaultConstraintValidators();
        assertTrue(registry.findConstraintValidator(Unhandled.class, String.class).isEmpty());
        assertTrue(registry.findConstraintValidator(Unhandled.class, String.class).isEmpty());
    }

    @Test
    void aPrototypeValidatorBeanIsNotKept() {
        try (ApplicationContext context = ApplicationContext.run()) {
            var registry = context.getBean(ConstraintValidatorRegistry.class);
            var first = registry.findConstraintValidator(Counted.class, String.class).orElseThrow();
            var second = registry.findConstraintValidator(Counted.class, String.class).orElseThrow();
            assertNotSame(first, second);
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = {})
    public @interface Unhandled {
        String message() default "unhandled";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = {})
    public @interface Counted {
        String message() default "counted";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @Prototype
    static class CountedValidator implements ConstraintValidator<Counted, String> {
        @Override
        public boolean isValid(@Nullable String value, AnnotationValue<Counted> annotationMetadata, ConstraintValidatorContext context) {
            return true;
        }
    }
}
