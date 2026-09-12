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

import io.micronaut.validation.validator.messages.DefaultMessages;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.Payload;
import jakarta.validation.ValidationException;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ValidateUnwrappedValue;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ElMessageInterpolatorTest {

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
        public List<Class<? extends jakarta.validation.ConstraintValidator<Annotation, ?>>> getConstraintValidatorClasses() {
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
