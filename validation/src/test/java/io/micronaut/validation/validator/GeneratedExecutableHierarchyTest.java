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
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import jakarta.validation.metadata.Scope;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeneratedExecutableHierarchyTest {
    @Test
    void inheritedCrossParameterConstraintsDoNotBecomeLocalDeclarations() {
        try (var factory = new DefaultValidatorFactory()) {
            var inherited = factory.getValidator().getConstraintsForClass(Inherited.class)
                .getConstraintsForMethod("checked", String.class).getCrossParameterDescriptor();
            assertEquals(1, inherited.getConstraintDescriptors().size());
            assertEquals(0, inherited.findConstraints().lookingAt(Scope.LOCAL_ELEMENT).getConstraintDescriptors().size());
            var overridden = factory.getValidator().getConstraintsForClass(Overridden.class)
                .getConstraintsForMethod("checked", String.class).getCrossParameterDescriptor();
            assertEquals(1, overridden.getConstraintDescriptors().size());
            assertEquals(0, overridden.findConstraints().lookingAt(Scope.LOCAL_ELEMENT).getConstraintDescriptors().size());
        }
    }

    @Test
    void repeatedReturnCascadeIsRejectedFromGeneratedDeclarations() throws Exception {
        try (var factory = new DefaultValidatorFactory()) {
            var method = RepeatedCascade.class.getMethod("value");
            assertThrows(ConstraintDeclarationException.class, () -> factory.getValidator().forExecutables()
                .validateReturnValue(new RepeatedCascade(), method, new Value()));
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = CrossValidator.class)
    @interface Cross {
        String message() default "cross";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    @Introspected
    @SupportedValidationTarget(ValidationTarget.PARAMETERS)
    public static class CrossValidator implements ConstraintValidator<Cross, Object[]> {
        @Override public boolean isValid(Object[] value, ConstraintValidatorContext context) { return true; }
    }

    @Introspected
    public static class Parent {
        @Cross public String checked(String value) { return value; }
    }
    @Introspected public static class Inherited extends Parent { }
    @Introspected public static class Overridden extends Parent {
        @Override public String checked(String value) { return value; }
    }
    @Introspected public static class Value { }
    @Introspected public static class CascadeParent {
        @Valid public Value value() { return new Value(); }
    }
    @Introspected public static class RepeatedCascade extends CascadeParent {
        @Override @Valid public Value value() { return new Value(); }
    }
}
