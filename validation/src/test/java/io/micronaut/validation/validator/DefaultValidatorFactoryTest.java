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
import io.micronaut.validation.validator.extractors.ValueExtractorDefinition;
import io.micronaut.validation.validator.extractors.ValueExtractorRegistry;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.ValueExtractor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultValidatorFactoryTest {

    @Test
    void factoryContextsKeepTheirSelectedMetadataSupport() {
        var configuration = new DefaultValidatorConfiguration();
        var reads = new AtomicInteger();
        var attributeReads = new AtomicInteger();
        ReflectionSupport support = (ReflectionSupport) Proxy.newProxyInstance(
            ReflectionSupport.class.getClassLoader(), new Class<?>[]{ReflectionSupport.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("withIntrospector") || method.getName().equals("withClassLoader")) {
                    return proxy;
                }
                if (method.getName().equals("readMember")) {
                    reads.incrementAndGet();
                }
                if (method.getName().equals("annotationAttributes")) {
                    attributeReads.incrementAndGet();
                }
                return method.invoke(new CompileTimeSupport(), arguments);
            });
        configuration.setReflectionSupport(support);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (var factory = new DefaultValidatorFactory(configuration)) {
            thread.setContextClassLoader(new ClassLoader(null) { });
            assertEquals(1, factory.getValidator().validate(new ProviderBean()).size());
            assertEquals(1, factory.usingContext().getValidator().validate(new ProviderBean()).size());
            assertEquals(2, reads.get());
            factory.getValidator().getConstraintsForClass(ProviderBean.class).getConstraintsForProperty("value")
                .getConstraintDescriptors().iterator().next().getAttributes();
            factory.usingContext().getValidator().getConstraintsForClass(ProviderBean.class).getConstraintsForProperty("value")
                .getConstraintDescriptors().iterator().next().getAttributes();
            assertTrue(attributeReads.get() >= 2);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static final class ProviderBean {
        @NotNull public String value;
    }

    @Test
    void usingContextDoesNotMutateFactoryConfiguration() {
        DefaultValidatorConfiguration configuration = new DefaultValidatorConfiguration();
        DefaultValidatorFactory factory = new DefaultValidatorFactory(configuration);
        MessageInterpolator defaultInterpolator = factory.getMessageInterpolator();

        Validator validator = factory.usingContext()
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

        factory.usingContext().addValueExtractor(definitionOf(new BoxExtractor()));

        assertTrue(factoryRegistry.findValueExtractors(Box.class).isEmpty());
    }

    @Test
    void contextKeepsItsTypeThroughTheMethodsOfTheSpecification() {
        DefaultValidatorFactory factory = new DefaultValidatorFactory(new DefaultValidatorConfiguration());
        BoxExtractor extractor = new BoxExtractor("chained");

        // a method of ValidatorContext returns the Micronaut context, so the full description still follows it
        Validator validator = factory.usingContext()
            .messageInterpolator(new TestMessageInterpolator())
            .addValueExtractor(definitionOf(extractor))
            .getValidator();

        Set<ConstraintViolation<BoxBean>> violations = validator.validate(new BoxBean(new Box<>(null)));
        assertEquals(1, violations.size());
        assertEquals("custom", violations.iterator().next().getMessage());
        assertEquals(1, extractor.extractions());
    }

    @Test
    void contextValueExtractorsOverrideFactoryConfiguration() {
        DefaultValidatorConfiguration configuration = new DefaultValidatorConfiguration();
        BoxExtractor factoryExtractor = new BoxExtractor("factory");
        BoxExtractor contextExtractor = new BoxExtractor("context");
        configuration.addValueExtractor(definitionOf(factoryExtractor));
        DefaultValidatorFactory factory = new DefaultValidatorFactory(configuration);

        Validator validator = factory.usingContext()
            .addValueExtractor(definitionOf(contextExtractor))
            .getValidator();

        Set<ConstraintViolation<BoxBean>> violations = validator.validate(new BoxBean(new Box<>(null)));

        assertEquals(1, violations.size());
        assertEquals(0, factoryExtractor.extractions());
        assertEquals(1, contextExtractor.extractions());

        assertEquals(1, factory.getValidator().validate(new BoxBean(new Box<>(null))).size());
        assertEquals(1, factoryExtractor.extractions());
    }

    @Test
    void delegatedConstraintValidatorFactoryHonorsTargetCompatibility() {
        InternalConstraintValidatorFactory factory = DefaultValidatorConfiguration.toInternalConstraintValidatorFactory(new TestConstraintValidatorFactory(), ReflectionSupport.get());

        assertNull(factory.getInstance(StringConstraintValidator.class, Integer.class, ConstraintTarget.IMPLICIT));
        assertNull(factory.getInstance(ParametersConstraintValidator.class, Object[].class, ConstraintTarget.RETURN_VALUE));
        assertNotNull(factory.getInstance(StringConstraintValidator.class, String.class, ConstraintTarget.IMPLICIT));
        assertNotNull(factory.getInstance(ParametersConstraintValidator.class, Object[].class, ConstraintTarget.PARAMETERS));
    }

    @Test
    void delegatedConstraintValidatorFactoryThrowsWhenDelegateReturnsNullForCompatibleValidator() {
        InternalConstraintValidatorFactory factory = DefaultValidatorConfiguration.toInternalConstraintValidatorFactory(new NullConstraintValidatorFactory(), ReflectionSupport.get());

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

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static final class BoxBean {
        Box<@NotNull String> box;

        BoxBean(Box<String> box) {
            this.box = box;
        }
    }

    /**
     * What the extractor extracts, said outright: the container it reads, the type of the value it yields
     * and which type argument carries it. Registering an extractor this way describes it without its class
     * being read, which is what the specification's own signature cannot do.
     */
    private static ValueExtractorDefinition<Box> definitionOf(BoxExtractor extractor) {
        return ValueExtractorDefinition.of(Box.class, Object.class, 0, false, extractor);
    }

    private static final class BoxExtractor implements ValueExtractor<Box<@ExtractedValue ?>> {

        private final String nodeName;
        private int extractions;

        BoxExtractor() {
            this("value");
        }

        BoxExtractor(String nodeName) {
            this.nodeName = nodeName;
        }

        @Override
        public void extractValues(Box<?> originalValue, ValueReceiver receiver) {
            extractions++;
            receiver.value(nodeName, originalValue.value());
        }

        int extractions() {
            return extractions;
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
