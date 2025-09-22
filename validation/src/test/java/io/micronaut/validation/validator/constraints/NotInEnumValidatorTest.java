/*
 * Copyright 2017-2023 original authors
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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.validation.Validated;
import io.micronaut.validation.annotation.NotInEnum;
import io.micronaut.validation.validator.Validator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
class NotInEnumValidatorTest {

    enum TestEnum {
        ONE, TWO
    }

    // Test for class fields (POJO)
    @Introspected
    static class TestPojo {
        @NotInEnum(value = TestEnum.class)
        private String field;

        @NotInEnum(value = TestEnum.class, caseSensitive = false)
        private String caseInsensitiveField;

        @NotInEnum(value = TestEnum.class)
        private TestEnum enumField;

        @NotInEnum(value = TestEnum.class)
        private Integer intField;

        public TestPojo(String field, String caseInsensitiveField, TestEnum enumField, Integer intField) {
            this.field = field;
            this.caseInsensitiveField = caseInsensitiveField;
            this.enumField = enumField;
            this.intField = intField;
        }

        public String getField() {
            return field;
        }

        public String getCaseInsensitiveField() {
            return caseInsensitiveField;
        }

        public TestEnum getEnumField() {
            return enumField;
        }

        public Integer getIntField() {
            return intField;
        }
    }

    @Inject
    Validator validator;

    @Test
    void testPojoFieldValidation() {

        // Valid: not in enum
        TestPojo valid = new TestPojo("THREE", "three", TestEnum.ONE, 123);
        Set<ConstraintViolation<TestPojo>> violations = validator.validate(valid);
        assertTrue(violations.isEmpty());

        // Invalid: in enum (case sensitive for field, insensitive for caseInsensitiveField)
        TestPojo invalid = new TestPojo("ONE", "one", null, null);
        violations = validator.validate(invalid);
        assertEquals(2, violations.size());

        // Mixed: case sensitive fails only if exact, insensitive fails on case ignore
        TestPojo mixed = new TestPojo("one", "ONE", TestEnum.TWO, 456);
        violations = validator.validate(mixed);
        assertEquals(1, violations.size()); // Only caseInsensitiveField fails since "ONE" matches ignore case

        // Null: valid
        TestPojo nullValue = new TestPojo(null, null, null, null);
        violations = validator.validate(nullValue);
        assertTrue(violations.isEmpty());
    }

    // Test for record types
    @Introspected
    record TestRecord(@NotInEnum(value = TestEnum.class) String component,
                      @NotInEnum(value = TestEnum.class, caseSensitive = false) String caseInsensitiveComponent,
                      @NotInEnum(value = TestEnum.class) TestEnum enumComponent,
                      @NotInEnum(value = TestEnum.class) Integer intComponent) {}

    @Test
    void testRecordValidation() {

        // Valid: not in enum
        TestRecord valid = new TestRecord("THREE", "three", TestEnum.ONE, 123);
        Set<ConstraintViolation<TestRecord>> violations = validator.validate(valid);
        assertTrue(violations.isEmpty());

        // Invalid: in enum
        TestRecord invalid = new TestRecord("ONE", "one", null, null);
        violations = validator.validate(invalid);
        assertEquals(2, violations.size());

        // Null: valid
        TestRecord nullValue = new TestRecord(null, null, null, null);
        violations = validator.validate(nullValue);
        assertTrue(violations.isEmpty());
    }

    @Validated
    @Singleton
    static class TestService {
        public void testMethod(@NotInEnum(value = TestEnum.class) String param,
                               @NotInEnum(value = TestEnum.class, caseSensitive = false) String caseInsensitiveParam,
                               @NotInEnum(value = TestEnum.class) TestEnum enumParam,
                               @NotInEnum(value = TestEnum.class) Integer intParam) {
        }
    }

    @Inject
    TestService service;

    @Test
    void testMethodArgumentValidation() {

        // Valid: no exception
        service.testMethod("THREE", "three", TestEnum.ONE, 123);

        // Invalid: throws with 2 violations
        ConstraintViolationException ex = assertThrows(ConstraintViolationException.class,
                () -> service.testMethod("ONE", "one", null, null));
        assertEquals(2, ex.getConstraintViolations().size());

        // Null: no exception
        service.testMethod(null, null, null, null);
    }

    @Test
    void testManualValidator() {
        NotInEnumValidator notInEnumValidator = new NotInEnumValidator();
        AnnotationValue<NotInEnum> ann = AnnotationValue.builder(NotInEnum.class).value(TestEnum.class).build();
        assertTrue(notInEnumValidator.isValid("THREE", ann, null));
        assertTrue(!notInEnumValidator.isValid("ONE", ann, null));
        assertTrue(notInEnumValidator.isValid(null, ann, null));
        assertTrue(notInEnumValidator.isValid(123, ann, null)); // Non-string, non-enum
        assertTrue(notInEnumValidator.isValid(TestEnum.ONE, ann, null)); // Enum type

        AnnotationValue<NotInEnum> insensitiveAnn = AnnotationValue.builder(NotInEnum.class)
                .value(TestEnum.class)
                .member("caseSensitive", false)
                .build();
        assertTrue(!notInEnumValidator.isValid("one", insensitiveAnn, null)); // Should fail (insensitive match)
        assertTrue(notInEnumValidator.isValid("three", insensitiveAnn, null)); // Should pass
    }

    @Test
    void testErrorMessage() {
        TestPojo invalid = new TestPojo("ONE", null, null, null);
        Set<ConstraintViolation<TestPojo>> violations = validator.validate(invalid);
        assertEquals(1, violations.size());

        ConstraintViolation<?> violation = violations.iterator().next();
        assertEquals("Not a supported value (ONE)", violation.getMessage());
    }
}
