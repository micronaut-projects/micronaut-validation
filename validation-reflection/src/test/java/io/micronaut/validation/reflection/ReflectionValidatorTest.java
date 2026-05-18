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
package io.micronaut.validation.reflection;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.validation.validator.Validator;
import io.micronaut.validation.validator.constraints.DefaultInternalConstraintValidatorFactory;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.Payload;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.annotation.Repeatable;
import java.lang.reflect.Constructor;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionValidatorTest {

    @Test
    void validatesBeanWithoutMicronautIntrospectionWhenReflectionFallbackIsPresent() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<PlainBean>> violations = validator.validate(new PlainBean(""));

            assertEquals(1, violations.size());
            ConstraintViolation<PlainBean> violation = violations.iterator().next();
            assertEquals("name", violation.getPropertyPath().toString());
            assertEquals("", violation.getInvalidValue());
        }
    }

    @Test
    void canDisableReflectionFallbackAtRuntime() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            ValidationException exception = assertThrows(ValidationException.class, () -> validator.validate(new PlainBean("")));

            assertTrue(exception.getMessage().contains("Bean introspection not found"));
        }
    }

    @Test
    void validatesConstructorParametersWithoutMicronautExecutableMetadata() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            Constructor<PlainBean> constructor = PlainBean.class.getDeclaredConstructor(String.class);

            Set<ConstraintViolation<PlainBean>> violations = validator.forExecutables()
                .validateConstructorParameters(constructor, new Object[]{""});

            assertEquals(1, violations.size());
            ConstraintViolation<PlainBean> violation = violations.iterator().next();
            assertEquals("", violation.getInvalidValue());
            Iterator<Path.Node> nodes = violation.getPropertyPath().iterator();
            Path.Node constructorNode = nodes.next();
            assertEquals(ElementKind.CONSTRUCTOR, constructorNode.getKind());
            assertEquals("PlainBean", constructorNode.getName());
            Path.ParameterNode parameterNode = nodes.next().as(Path.ParameterNode.class);
            assertEquals(ElementKind.PARAMETER, parameterNode.getKind());
            assertEquals("name", parameterNode.getName());
            assertEquals(0, parameterNode.getParameterIndex());
            assertFalse(nodes.hasNext());
        }
    }

    @Test
    void validatesReturnValueWithoutMicronautExecutableMetadata() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<PlainBean>> violations = validator.forExecutables()
                .validateReturnValue(new PlainBean("x"), PlainBean.class.getDeclaredMethod("displayName"), "");

            assertEquals(1, violations.size());
            ConstraintViolation<PlainBean> violation = violations.iterator().next();
            assertEquals("", violation.getInvalidValue());
            Iterator<Path.Node> nodes = violation.getPropertyPath().iterator();
            Path.Node methodNode = nodes.next();
            assertEquals(ElementKind.METHOD, methodNode.getKind());
            assertEquals("displayName", methodNode.getName());
            Path.Node returnValueNode = nodes.next();
            assertEquals(ElementKind.RETURN_VALUE, returnValueNode.getKind());
            assertEquals("<return value>", returnValueNode.getName());
            assertFalse(nodes.hasNext());
        }
    }

    @Test
    void rejectsNullArgumentsWithIllegalArgumentException() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            assertThrows(IllegalArgumentException.class, () -> validator.validate(null));
            assertThrows(IllegalArgumentException.class, () -> validator.validateProperty(null, "name"));
            assertThrows(IllegalArgumentException.class, () -> validator.validateValue(null, "name", ""));
            assertThrows(IllegalArgumentException.class, () -> validator.getConstraintsForClass(null));
        }
    }

    @Test
    void instantiatesPrivateConstraintValidatorReflectively() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<PrivateConstraintBean>> violations = validator.validate(new PrivateConstraintBean("bad"));

            assertEquals(1, violations.size());
            assertEquals("name", violations.iterator().next().getPropertyPath().toString());
        }
    }

    @Test
    void readsPrivateRepeatableConstraintContainerReflectively() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<RepeatablePrivateConstraintBean>> violations = validator.validate(new RepeatablePrivateConstraintBean("bad"));

            assertEquals(2, violations.size());
        }
    }

    @Test
    void resolvesReflectiveConstraintValidatorForTargetType() {
        ReflectionConstraintValidatorFactory factory = new ReflectionConstraintValidatorFactory(
            new DefaultInternalConstraintValidatorFactory(BeanIntrospector.SHARED, null)
        );

        assertNotNull(factory.getInstance(PrivateConstraintValidator.class, String.class, ConstraintTarget.IMPLICIT));
    }

    static final class PlainBean {
        @NotBlank
        private final String name;

        PlainBean(@NotBlank String name) {
            this.name = name;
        }

        @NotBlank
        String displayName() {
            return name;
        }
    }

    private record PrivateConstraintBean(
        @PrivateConstraint String name
    ) {
    }

    @Target(FIELD)
    @Retention(RUNTIME)
    @Repeatable(PrivateConstraints.class)
    @Constraint(validatedBy = PrivateConstraintValidator.class)
    private @interface PrivateConstraint {
        String message() default "invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @Target(FIELD)
    @Retention(RUNTIME)
    private @interface PrivateConstraints {
        PrivateConstraint[] value();
    }

    private static final class PrivateConstraintValidator implements ConstraintValidator<PrivateConstraint, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return false;
        }
    }

    private record RepeatablePrivateConstraintBean(
        @PrivateConstraint
        @PrivateConstraint
        String name
    ) {
    }
}
