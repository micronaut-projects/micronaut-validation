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
import io.micronaut.validation.annotation.InEnum;
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
class InEnumValidatorTest {

    enum TestEnum {
        ONE, TWO
    }

    // Test for class fields (POJO)
    @Introspected
    static class TestPojo {
        @InEnum(value = TestEnum.class)
        private String field;

        @InEnum(value = TestEnum.class, caseSensitive = false)
        private String caseInsensitiveField;

        @InEnum(value = TestEnum.class)
        private TestEnum enumField;

        @InEnum(value = TestEnum.class)
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

        // Valid: in enum
        TestPojo valid = new TestPojo("ONE", "one", TestEnum.ONE, 123);
        Set<ConstraintViolation<TestPojo>> violations = validator.validate(valid);
        assertTrue(violations.isEmpty());

        // Invalid: not in enum (case sensitive for field, insensitive for caseInsensitiveField)
        TestPojo invalid = new TestPojo("THREE", "three", null, null);
        violations = validator.validate(invalid);
        assertEquals(2, violations.size());

        // Mixed: case sensitive passes only if exact, insensitive passes on case ignore
        TestPojo mixed = new TestPojo("one", "TWO", TestEnum.TWO, 456);
        violations = validator.validate(mixed);
        assertEquals(1, violations.size()); // Only field fails since "one" doesn't match exact

        // Null: valid
        TestPojo nullValue = new TestPojo(null, null, null, null);
        violations = validator.validate(nullValue);
        assertTrue(violations.isEmpty());
    }

    // Test for record types
    @Introspected
    record TestRecord(@InEnum(value = TestEnum.class) String component,
                      @InEnum(value = TestEnum.class, caseSensitive = false) String caseInsensitiveComponent,
                      @InEnum(value = TestEnum.class) TestEnum enumComponent,
                      @InEnum(value = TestEnum.class) Integer intComponent) {}

    @Test
    void testRecordValidation() {

        // Valid: in enum
        TestRecord valid = new TestRecord("ONE", "one", TestEnum.ONE, 123);
        Set<ConstraintViolation<TestRecord>> violations = validator.validate(valid);
        assertTrue(violations.isEmpty());

        // Invalid: not in enum
        TestRecord invalid = new TestRecord("THREE", "three", null, null);
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
        public void testMethod(@InEnum(value = TestEnum.class) String param,
                               @InEnum(value = TestEnum.class, caseSensitive = false) String caseInsensitiveParam,
                               @InEnum(value = TestEnum.class) TestEnum enumParam,
                               @InEnum(value = TestEnum.class) Integer intParam) {
        }
    }

    @Inject
    TestService service;

    @Test
    void testMethodArgumentValidation() {

        // Valid: no exception
        service.testMethod("ONE", "one", TestEnum.ONE, 123);

        // Invalid: throws with 2 violations
        ConstraintViolationException ex = assertThrows(ConstraintViolationException.class,
                () -> service.testMethod("THREE", "three", null, null));
        assertEquals(2, ex.getConstraintViolations().size());

        // Null: no exception
        service.testMethod(null, null, null, null);
    }

    @Test
    void testManualValidator() {
        InEnumValidator inEnumValidator = new InEnumValidator();
        AnnotationValue<InEnum> ann = AnnotationValue.builder(InEnum.class).value(TestEnum.class).build();
        assertTrue(inEnumValidator.isValid("ONE", ann, null));
        assertTrue(!inEnumValidator.isValid("THREE", ann, null));
        assertTrue(inEnumValidator.isValid(null, ann, null));
        assertTrue(inEnumValidator.isValid(123, ann, null)); // Non-string, non-enum
        assertTrue(inEnumValidator.isValid(TestEnum.ONE, ann, null)); // Enum type

        AnnotationValue<InEnum> insensitiveAnn = AnnotationValue.builder(InEnum.class)
                .value(TestEnum.class)
                .member("caseSensitive", false)
                .build();
        assertTrue(inEnumValidator.isValid("one", insensitiveAnn, null)); // Should pass (insensitive match)
        assertTrue(!inEnumValidator.isValid("three", insensitiveAnn, null)); // Should fail
    }

    @Test
    void testErrorMessage() {
        TestPojo invalid = new TestPojo("THREE", null, null, null);
        Set<ConstraintViolation<TestPojo>> violations = validator.validate(invalid);
        assertEquals(1, violations.size());

        ConstraintViolation<?> violation = violations.iterator().next();
        assertEquals("Not in the list of supported values (THREE)", violation.getMessage());
    }
}
