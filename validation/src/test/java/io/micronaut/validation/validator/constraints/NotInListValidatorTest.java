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
import io.micronaut.validation.annotation.NotInList;
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
class NotInListValidatorTest {

    @Inject
    Validator validator;

    enum TestEnum {
        ONE, TWO, THREE
    }

    // Test for class fields (POJO)
    @Introspected
    static class TestPojo {
        @NotInList(value = {"ONE", "TWO"})
        private String field;

        @NotInList(value = {"ONE", "TWO"}, caseSensitive = false)
        private String caseInsensitiveField;

        @NotInList(value = {"ONE", "TWO"})
        private TestEnum enumField;

        @NotInList(value = {"ONE", "TWO"})
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

    @Test
    void testPojoFieldValidation() {
        // Valid: not in list
        TestPojo valid = new TestPojo("THREE", "three", TestEnum.THREE, 123);
        Set<ConstraintViolation<TestPojo>> violations = validator.validate(valid);
        assertTrue(violations.isEmpty());

        // Invalid: in list
        TestPojo invalid = new TestPojo("ONE", "one", null, null);
        violations = validator.validate(invalid);
        assertEquals(2, violations.size());

        // Mixed
        TestPojo mixed = new TestPojo("one", "TWO", TestEnum.THREE, 456);
        violations = validator.validate(mixed);
        assertEquals(1, violations.size()); // caseInsensitiveField fails

        // Null: valid
        TestPojo nullValue = new TestPojo(null, null, null, null);
        violations = validator.validate(nullValue);
        assertTrue(violations.isEmpty());

    }

    // Test for record types
    @Introspected
    record TestRecord(@NotInList(value = {"ONE", "TWO"}) String component,
                      @NotInList(value = {"ONE", "TWO"}, caseSensitive = false) String caseInsensitiveComponent,
                      @NotInList(value = {"ONE", "TWO"}) TestEnum enumComponent,
                      @NotInList(value = {"ONE", "TWO"}) Integer intComponent) {}

    @Test
    void testRecordValidation() {
        // Valid
        TestRecord valid = new TestRecord("THREE", "three", TestEnum.THREE, 123);
        Set<ConstraintViolation<TestRecord>> violations = validator.validate(valid);
        assertTrue(violations.isEmpty());

        // Invalid
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
        public void testMethod(@NotInList(value = {"ONE", "TWO"}) String param,
                               @NotInList(value = {"ONE", "TWO"}, caseSensitive = false) String caseInsensitiveParam,
                               @NotInList(value = {"ONE", "TWO"}) TestEnum enumParam,
                               @NotInList(value = {"ONE", "TWO"}) Integer intParam) {
        }
    }

    @Inject
    TestService service;

    @Test
    void testMethodArgumentValidation() {
        // Valid
        service.testMethod("THREE", "three", TestEnum.THREE, 123);

        // Invalid
        ConstraintViolationException ex = assertThrows(ConstraintViolationException.class,
                () -> service.testMethod("ONE", "one", null, null));
        assertEquals(2, ex.getConstraintViolations().size());

        // Null
        service.testMethod(null, null, null, null);
    }

    @Test
    void testManualValidator() {
        NotInListValidator notInListValidator = new NotInListValidator();
        AnnotationValue<NotInList> ann = AnnotationValue.builder(NotInList.class).values("ONE", "TWO").build();
        assertTrue(notInListValidator.isValid("THREE", ann, null));
        assertTrue(!notInListValidator.isValid("ONE", ann, null));
        assertTrue(notInListValidator.isValid(null, ann, null));
        assertTrue(notInListValidator.isValid(123, ann, null));
        assertTrue(notInListValidator.isValid(TestEnum.THREE, ann, null));

        AnnotationValue<NotInList> insensitiveAnn = AnnotationValue.builder(NotInList.class)
                .values("ONE", "TWO")
                .member("caseSensitive", false)
                .build();
        assertTrue(!notInListValidator.isValid("one", insensitiveAnn, null));
        assertTrue(notInListValidator.isValid("three", insensitiveAnn, null));
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
