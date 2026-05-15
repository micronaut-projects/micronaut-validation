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
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DefaultValidatorFactoryTest {

    @Test
    void usingContextDoesNotMutateFactoryConfiguration() {
        DefaultValidatorConfiguration configuration = new DefaultValidatorConfiguration();
        DefaultValidatorFactory factory = new DefaultValidatorFactory(configuration);
        MessageInterpolator defaultInterpolator = factory.getMessageInterpolator();

        jakarta.validation.Validator validator = factory.usingContext()
            .messageInterpolator(new TestMessageInterpolator())
            .getValidator();

        Set<ConstraintViolation<Person>> violations = validator.validate(new Person());
        assertEquals(1, violations.size());
        assertEquals("custom", violations.iterator().next().getMessage());
        assertSame(defaultInterpolator, factory.getMessageInterpolator());
    }

    @Test
    void getConstraintValidatorFactoryReturnsConfiguredFactory() {
        DefaultValidatorConfiguration configuration = new DefaultValidatorConfiguration();
        ConstraintValidatorFactory constraintValidatorFactory = new TestConstraintValidatorFactory();
        configuration.constraintValidatorFactory(constraintValidatorFactory);

        DefaultValidatorFactory factory = new DefaultValidatorFactory(configuration);

        assertSame(constraintValidatorFactory, factory.getConstraintValidatorFactory());
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static final class Person {
        @NotNull
        String name;
    }

    private static final class TestMessageInterpolator implements MessageInterpolator {

        @Override
        public String interpolate(String messageTemplate, Context context) {
            return "custom";
        }

        @Override
        public String interpolate(String messageTemplate, Context context, Locale locale) {
            return "custom";
        }
    }

    private static final class TestConstraintValidatorFactory implements ConstraintValidatorFactory {

        @Override
        public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
            return null;
        }

        @Override
        public void releaseInstance(ConstraintValidator<?, ?> instance) {
        }
    }
}
