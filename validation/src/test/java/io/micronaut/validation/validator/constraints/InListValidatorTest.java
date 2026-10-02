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
import io.micronaut.validation.annotation.InList;
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
class InListValidatorTest {

    @Inject
    Validator validator;

    enum TestEnum {
        ONE, TWO
    }

    // Test for class fields (POJO)
    @Introspected
    static class TestPojo {
        @InList(value = {"ONE", "TWO"})
        private String field;

        @InList(value = {"ONE", "TWO"}, caseSensitive = false)
        private String caseInsensitiveField;

        @InList(value = {"ONE", "TWO"})
        private TestEnum enumField;

        @InList(value = {"ONE", "TWO"})
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
        // Valid: in list
        TestPojo valid = new TestPojo("ONE", "one", TestEnum.ONE, 123);
        Set<ConstraintViolation<TestPojo>> violations = validator.validate(valid);
        assertTrue(violations.isEmpty());

        // Invalid: not in list
        TestPojo invalid = new TestPojo("THREE", "three", null, null);
        violations = validator.validate(invalid);
        assertEquals(2, violations.size());

        // Mixed
        TestPojo mixed = new TestPojo("one", "TWO", TestEnum.TWO, 456);
        violations = validator.validate(mixed);
        assertEquals(1, violations.size()); // field fails (case sensitive)

        // Null: valid
        TestPojo nullValue = new TestPojo(null, null, null, null);
        violations = validator.validate(nullValue);
        assertTrue(violations.isEmpty());

    }

    // Test for record types
    @Introspected
    record TestRecord(@InList(value = {"ONE", "TWO"}) String component,
                      @InList(value = {"ONE", "TWO"}, caseSensitive = false) String caseInsensitiveComponent,
                      @InList(value = {"ONE", "TWO"}) TestEnum enumComponent,
                      @InList(value = {"ONE", "TWO"}) Integer intComponent) {}

    @Test
    void testRecordValidation() {
        // Valid
        TestRecord valid = new TestRecord("ONE", "one", TestEnum.ONE, 123);
        Set<ConstraintViolation<TestRecord>> violations = validator.validate(valid);
        assertTrue(violations.isEmpty());

        // Invalid
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
        public void testMethod(@InList(value = {"ONE", "TWO"}) String param,
                               @InList(value = {"ONE", "TWO"}, caseSensitive = false) String caseInsensitiveParam,
                               @InList(value = {"ONE", "TWO"}) TestEnum enumParam,
                               @InList(value = {"ONE", "TWO"}) Integer intParam) {
        }
    }

    @Inject
    TestService service;

    @Test
    void testMethodArgumentValidation() {
        // Valid
        service.testMethod("ONE", "one", TestEnum.ONE, 123);

        // Invalid
        ConstraintViolationException ex = assertThrows(ConstraintViolationException.class,
                () -> service.testMethod("THREE", "three", null, null));
        assertEquals(2, ex.getConstraintViolations().size());

        // Null
        service.testMethod(null, null, null, null);
    }

    @Test
    void testManualValidator() {
        InListValidator inListValidator = new InListValidator();
        AnnotationValue<InList> ann = AnnotationValue.builder(InList.class).values("ONE", "TWO").build();
        assertTrue(inListValidator.isValid("ONE", ann, null));
        assertTrue(!inListValidator.isValid("THREE", ann, null));
        assertTrue(inListValidator.isValid(null, ann, null));
        assertTrue(inListValidator.isValid(123, ann, null));
        assertTrue(inListValidator.isValid(TestEnum.ONE, ann, null));

        AnnotationValue<InList> insensitiveAnn = AnnotationValue.builder(InList.class)
                .values("ONE", "TWO")
                .member("caseSensitive", false)
                .build();
        assertTrue(inListValidator.isValid("one", insensitiveAnn, null));
        assertTrue(!inListValidator.isValid("three", insensitiveAnn, null));
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
