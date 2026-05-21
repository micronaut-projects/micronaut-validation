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
import io.micronaut.validation.validator.constraints.InternalConstraintValidatorFactory;
import io.micronaut.validation.validator.extractors.ValueExtractorRegistry;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.ValueExtractor;
import jakarta.validation.valueextraction.ValueExtractorDeclarationException;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void contextValueExtractorsDoNotMutateFactoryConfiguration() {
        DefaultValidatorConfiguration configuration = new DefaultValidatorConfiguration();
        DefaultValidatorFactory factory = new DefaultValidatorFactory(configuration);
        ValueExtractorRegistry factoryRegistry = configuration.getValueExtractorRegistry();

        factory.usingContext().addValueExtractor(new BoxExtractor());

        assertTrue(factoryRegistry.findValueExtractors(Box.class).isEmpty());
    }

    @Test
    void contextValueExtractorsDetectDuplicatesFromFactoryConfiguration() {
        DefaultValidatorConfiguration configuration = new DefaultValidatorConfiguration();
        configuration.addValueExtractor(new BoxExtractor());
        DefaultValidatorFactory factory = new DefaultValidatorFactory(configuration);

        assertThrows(
            ValueExtractorDeclarationException.class,
            () -> factory.usingContext().addValueExtractor(new BoxExtractor())
        );
    }

    @Test
    void delegatedConstraintValidatorFactoryHonorsTargetCompatibility() {
        InternalConstraintValidatorFactory factory = DefaultValidatorConfiguration.toInternalConstraintValidatorFactory(new TestConstraintValidatorFactory());

        assertNull(factory.getInstance(StringConstraintValidator.class, Integer.class, ConstraintTarget.IMPLICIT));
        assertNull(factory.getInstance(ParametersConstraintValidator.class, Object[].class, ConstraintTarget.RETURN_VALUE));
        assertNotNull(factory.getInstance(StringConstraintValidator.class, String.class, ConstraintTarget.IMPLICIT));
        assertNotNull(factory.getInstance(ParametersConstraintValidator.class, Object[].class, ConstraintTarget.PARAMETERS));
    }

    @Test
    void delegatedConstraintValidatorFactoryThrowsWhenDelegateReturnsNullForCompatibleValidator() {
        InternalConstraintValidatorFactory factory = DefaultValidatorConfiguration.toInternalConstraintValidatorFactory(new NullConstraintValidatorFactory());

        assertNull(factory.getInstance(StringConstraintValidator.class, Integer.class, ConstraintTarget.IMPLICIT));
        assertThrows(
            ValidationException.class,
            () -> factory.getInstance(StringConstraintValidator.class, String.class, ConstraintTarget.IMPLICIT)
        );
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static final class Person {
        @NotNull
        String name;
    }

    private record Box<T>(T value) {
    }

    private static final class BoxExtractor implements ValueExtractor<Box<@ExtractedValue ?>> {

        @Override
        public void extractValues(Box<?> originalValue, ValueReceiver receiver) {
            receiver.value("value", originalValue.value());
        }
    }

    private static final class StringConstraintValidator implements ConstraintValidator<NotNull, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return true;
        }
    }

    @SupportedValidationTarget(ValidationTarget.PARAMETERS)
    private static final class ParametersConstraintValidator implements ConstraintValidator<NotNull, Object[]> {

        @Override
        public boolean isValid(Object[] value, ConstraintValidatorContext context) {
            return true;
        }
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
            if (key == StringConstraintValidator.class) {
                return (T) new StringConstraintValidator();
            }
            if (key == ParametersConstraintValidator.class) {
                return (T) new ParametersConstraintValidator();
            }
            throw new IllegalArgumentException("Unsupported validator: " + key.getName());
        }

        @Override
        public void releaseInstance(ConstraintValidator<?, ?> instance) {
            // The tests do not allocate external resources for validators.
        }
    }

    private static final class NullConstraintValidatorFactory implements ConstraintValidatorFactory {

        @Override
        public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
            return null;
        }

        @Override
        public void releaseInstance(ConstraintValidator<?, ?> instance) {
            // The tests do not allocate external resources for validators.
        }
    }
}
