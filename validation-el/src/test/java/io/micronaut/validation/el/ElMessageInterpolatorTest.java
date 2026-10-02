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

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.el.CompiledExpressionFactory;
import io.micronaut.validation.validator.messages.DefaultMessages;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.Payload;
import jakarta.validation.ValidationException;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ValidateUnwrappedValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ElMessageInterpolatorTest {

    @Test
    void evaluatesCompiledMessagesAlongsideRuntimeMessages() {
        var interpolator = new ElMessageInterpolator(new DefaultMessages(), null);
        assertEquals("ABC", interpolator.interpolate("${validatedValue.toUpperCase()}", new TestContext("abc", Map.of())));
        assertEquals("runtime 7", interpolator.interpolate("runtime ${3 + 4}", new TestContext("abc", Map.of())));
        // A factory without an interpreter proves this expression was compiled and discovered.
        var factory = new CompiledExpressionFactory(ElExpressionSources.load(getClass().getClassLoader()));
        var context = new ValidationELContext(BeanIntrospector.forClassLoader(getClass().getClassLoader()));
        context.getVariableMapper().setVariable("validatedValue", factory.createValueExpression("abc", String.class));
        assertEquals("ABC", factory.createValueExpression(context, "${validatedValue.toUpperCase()}", Object.class).getValue(context));
    }

    @Test
    void usesTheApplicationLoaderAfterTheThreadLoaderChanges() {
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override public InputStream getResourceAsStream(String name) {
                if (name.equals("ValidationMessages.properties")) {
                    return new ByteArrayInputStream("review.loader=application-loader".getBytes(StandardCharsets.UTF_8));
                }
                return super.getResourceAsStream(name);
            }
        };
        var interpolator = new ElMessageInterpolatorProvider().create(loader).orElseThrow();
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(new ClassLoader(null) { });
            assertEquals("application-loader", interpolator.interpolate("{review.loader}", new TestContext("abc", Map.of()), Locale.ROOT));
            assertEquals("generated", interpolator.interpolate("${validatedValue.label}", new TestContext(new GeneratedBean(), Map.of()), Locale.ROOT));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void interpolatesJakartaElExpressionsAndConstraintAttributes() {
        ElMessageInterpolator interpolator = new ElMessageInterpolator(new DefaultMessages(), null);

        String message = interpolator.interpolate(
            "value ${validatedValue.toUpperCase()} must be at least {min}",
            new TestContext("abc", Map.of("min", 3))
        );

        assertEquals("value ABC must be at least 3", message);
    }

    @Test
    void exposesSpecElVariables() {
        ElMessageInterpolator interpolator = new ElMessageInterpolator(new DefaultMessages(), null);

        String message = interpolator.interpolate(
            "groups: ${groups[0].simpleName}, payload: ${payload[0].simpleName}",
            new TestContext("abc", Map.of(), Set.of(TestGroup.class), Set.of(TestPayload.class))
        );

        assertEquals("groups: TestGroup, payload: TestPayload", message);
    }

    @Test
    void exposesLocaleAwareFormatter() {
        ElMessageInterpolator interpolator = new ElMessageInterpolator(new DefaultMessages(), null);

        String message = interpolator.interpolate(
            "${formatter.format('%1$.2f', validatedValue)}",
            new TestContext(98.12345678, Map.of()),
            Locale.GERMAN
        );

        assertEquals("98,12", message);
    }

    @Test
    void leavesInvalidElExpressionUnchanged() {
        ElMessageInterpolator interpolator = new ElMessageInterpolator(new DefaultMessages(), null);

        String message = interpolator.interpolate("${unknown} ${1*}", new TestContext("abc", Map.of()));

        assertEquals("${unknown} ${1*}", message);
    }

    @Test
    void interpolatesUserBundleMessagesRecursively() {
        ElMessageInterpolator interpolator = new ElMessageInterpolator(new DefaultMessages(), null);

        String message = interpolator.interpolate("{replace.in.user.bundle1}", new TestContext("abc", Map.of()));

        assertEquals("recursion worked", message);
    }

    @Test
    void interpolatesUserBundleMessagesWithLocale() {
        ElMessageInterpolator interpolator = new ElMessageInterpolator(new DefaultMessages(), null);

        String message = interpolator.interpolate("{jakarta.validation.constraints.NotNull.message}", new TestContext("abc", Map.of()), Locale.GERMAN);

        assertEquals("kann nicht null sein", message);
    }

    @Test
    void interpolatesParametersBeforeElExpressions() {
        ElMessageInterpolator interpolator = new ElMessageInterpolator(new DefaultMessages(), null);

        assertEquals("must be $5 at least", interpolator.interpolate("must be ${value} at least", new TestContext(3, Map.of("value", 5))));
        assertEquals("must be 10 at least", interpolator.interpolate("must be ${value * 2} at least", new TestContext(3, Map.of("value", 5))));
    }

    @Test
    void leavesElExpressionsForValidatedValueToElPass() {
        ElMessageInterpolator interpolator = new ElMessageInterpolator(new DefaultMessages(), null);

        assertEquals("abc", interpolator.interpolate("${validatedValue}", new TestContext("abc", Map.of())));
    }

    @Test
    void leavesElExpressionUnchangedWhenValidatedValueToStringThrows() {
        ElMessageInterpolator interpolator = new ElMessageInterpolator(new DefaultMessages(), null);

        assertEquals("${validatedValue}", interpolator.interpolate("${validatedValue}", new TestContext(new ThrowingToString(), Map.of())));
    }

    @Test
    void preservesEscapesAndDoesNotInterpolateAttributeValuesAgain() {
        var interpolator = new ElMessageInterpolator(new DefaultMessages(), null);
        var context = new TestContext("abc", Map.of("min", 3, "value", "{min} ${validatedValue}"));
        assertEquals("{min}", interpolator.interpolate("\\{min}", context));
        assertEquals("${validatedValue}", interpolator.interpolate("\\${validatedValue}", context));
        assertEquals("{min} ${validatedValue}", interpolator.interpolate("{value}", context));
        assertEquals("\\3", interpolator.interpolate("\\\\{min}", context));
        assertEquals("\\q", interpolator.interpolate("\\q", context));
    }

    @Test
    void supportsQuotedBracesAndCollectionLiterals() {
        var interpolator = new ElMessageInterpolator(new DefaultMessages(), null);
        assertEquals("}", interpolator.interpolate("${'}'}", new TestContext("abc", Map.of())));
        assertEquals("2", interpolator.interpolate("${{'a': 2}['a']}", new TestContext("abc", Map.of())));
    }

    @Test
    void readsGeneratedPropertiesButNeverInvokesAnUnintrospectedGetter() {
        var interpolator = new ElMessageInterpolator(new DefaultMessages(), null);
        assertEquals("generated", interpolator.interpolate("${validatedValue.label}", new TestContext(new GeneratedBean(), Map.of())));
        assertEquals("${validatedValue.label}", interpolator.interpolate("${validatedValue.label}", new TestContext(new PlainBean(), Map.of())));
        assertEquals("${validatedValue.getLabel()}", interpolator.interpolate("${validatedValue.getLabel()}", new TestContext(new PlainBean(), Map.of())));
        assertEquals(0, PlainBean.reads);
    }

    @Test
    void preservesSandboxForClassProperties() {
        var interpolator = new ElMessageInterpolator(new DefaultMessages(), null);
        assertEquals("${validatedValue.classLoader}", interpolator.interpolate("${validatedValue.classLoader}", new TestContext(TestGroup.class, Map.of())));
    }

    @Test
    void reflectionCompanionsAreAbsent() {
        if (Boolean.getBoolean("validation.test.el.reflectionCompanion")) {
            Assertions.assertDoesNotThrow(() ->
                Class.forName("io.micronaut.el.interpreter.reflection.ReflectiveELMethodExecutor"));
            return;
        }
        Assertions.assertThrows(ClassNotFoundException.class,
            () -> Class.forName("io.micronaut.validation.reflection.ReflectionValidationSupport"));
        Assertions.assertThrows(ClassNotFoundException.class,
            () -> Class.forName("io.micronaut.el.interpreter.reflection.ReflectiveELMethodExecutor"));
    }

    @Introspected
    public static final class GeneratedBean {
        public String getLabel() {
            return "generated";
        }
    }

    public static final class PlainBean {
        static int reads;
        public String getLabel() {
            reads++;
            throw new AssertionError("An ungenerated getter must never be invoked");
        }
    }

    @Test
    void formatterHandlesSeveralArgumentsAndAnExplicitArray() {
        var interpolator = new ElMessageInterpolator(new DefaultMessages(), null);
        assertEquals("a:2", interpolator.interpolate("${formatter.format('%s:%d', 'a', 2)}", new TestContext("ignored", Map.of())));
        assertEquals("a:2", interpolator.interpolate("${formatter.format('%s:%d', validatedValue)}", new TestContext(new Object[]{"a", 2}, Map.of())));
    }

    @Test
    void bundleCyclesTerminateAndEscapesSurviveExpansion() {
        var interpolator = new ElMessageInterpolator(new DefaultMessages(), null);
        var context = new TestContext("abc", Map.of("min", 3));
        assertEquals("{review.cycle}", interpolator.interpolate("{review.cycle}", context));
        assertEquals("{min}", interpolator.interpolate("{review.escaped}", context));
        assertEquals("done", interpolator.interpolate("{review.deep.0}", context));
    }

    private static final class ThrowingToString {
        @Override
        public String toString() {
            throw new IllegalStateException("boom");
        }
    }

    private record TestContext(
        Object value,
        Map<String, Object> attributes,
        Set<Class<?>> groups,
        Set<Class<? extends Payload>> payload
    ) implements MessageInterpolator.Context {

        private TestContext(Object value, Map<String, Object> attributes) {
            this(value, attributes, Set.of(), Set.of());
        }

        @Override
        public ConstraintDescriptor<?> getConstraintDescriptor() {
            return new TestConstraintDescriptor(attributes, groups, payload);
        }

        @Override
        public Object getValidatedValue() {
            return value;
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new ValidationException("Unsupported unwrap");
        }
    }

    private record TestConstraintDescriptor(
        Map<String, Object> attributes,
        Set<Class<?>> groups,
        Set<Class<? extends Payload>> payload
    ) implements ConstraintDescriptor<Annotation> {

        @Override
        public Annotation getAnnotation() {
            return null;
        }

        @Override
        public String getMessageTemplate() {
            return "";
        }

        @Override
        public Set<Class<?>> getGroups() {
            return groups;
        }

        @Override
        public Set<Class<? extends Payload>> getPayload() {
            return payload;
        }

        @Override
        public ConstraintTarget getValidationAppliesTo() {
            return ConstraintTarget.IMPLICIT;
        }

        @Override
        public List<Class<? extends ConstraintValidator<Annotation, ?>>> getConstraintValidatorClasses() {
            return List.of();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Set<ConstraintDescriptor<?>> getComposingConstraints() {
            return Set.of();
        }

        @Override
        public boolean isReportAsSingleViolation() {
            return false;
        }

        @Override
        public ValidateUnwrappedValue getValueUnwrapping() {
            return ValidateUnwrappedValue.DEFAULT;
        }

        @Override
        public <U> U unwrap(Class<U> type) {
            throw new ValidationException("Unsupported unwrap");
        }
    }

    private interface TestGroup {
    }

    private interface TestPayload extends Payload {
    }
}
