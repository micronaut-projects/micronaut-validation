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

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The type a validator validates may be parameterized only with unbounded wildcards: {@code List<Object>} is not one,
 * and a validator of it would be handed a {@code List<String>}.
 */
class WildcardValidatedTypeDefinitionTest {

    @Test
    void aValidatorOfAListOfObjectIsAnInvalidDefinition() {
        var support = new ReflectionValidationSupport();
        assertThrows(ConstraintDefinitionException.class, () -> support.checkConstraintDefinition(ObjectList.class));
        assertThrows(ConstraintDefinitionException.class, () -> support.checkConstraintDefinition(InheritedObjectList.class));
        assertDoesNotThrow(() -> support.checkConstraintDefinition(WildcardList.class));
        assertDoesNotThrow(() -> support.checkConstraintDefinition(RawList.class));
    }

    @Constraint(validatedBy = ObjectListValidator.class)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ObjectList {
        String message() default "invalid";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    @Constraint(validatedBy = InheritedObjectListValidator.class)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface InheritedObjectList {
        String message() default "invalid";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    @Constraint(validatedBy = WildcardListValidator.class)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface WildcardList {
        String message() default "invalid";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    @Constraint(validatedBy = RawListValidator.class)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface RawList {
        String message() default "invalid";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class ObjectListValidator implements ConstraintValidator<ObjectList, List<Object>> {
        @Override
        public boolean isValid(List<Object> value, ConstraintValidatorContext context) {
            return true;
        }
    }

    public abstract static class AbstractValidator<A extends java.lang.annotation.Annotation, T> implements ConstraintValidator<A, T> {
        @Override
        public boolean isValid(T value, ConstraintValidatorContext context) {
            return true;
        }
    }

    public static class InheritedObjectListValidator extends AbstractValidator<InheritedObjectList, List<Object>> {
    }

    public static class WildcardListValidator implements ConstraintValidator<WildcardList, List<?>> {
        @Override
        public boolean isValid(List<?> value, ConstraintValidatorContext context) {
            return true;
        }
    }

    @SuppressWarnings("rawtypes")
    public static class RawListValidator implements ConstraintValidator<RawList, List> {
        @Override
        public boolean isValid(List value, ConstraintValidatorContext context) {
            return true;
        }
    }
}
