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

import io.micronaut.core.annotation.Introspected;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.validation.Validated;
import io.micronaut.validation.annotation.UniqueElements;
import io.micronaut.validation.validator.Validator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
class UniqueElementsValidatorTest {

    @Inject
    Validator validator;

    // Test for class fields (POJO)
    @Introspected
    static class TestPojo {
        @UniqueElements
        private List<String> stringList;

        @UniqueElements
        private String[] stringArray;

        @UniqueElements
        private int[] intArray;

        @UniqueElements
        private Integer nonCollection;

        public TestPojo(List<String> stringList, String[] stringArray, int[] intArray, Integer nonCollection) {
            this.stringList = stringList;
            this.stringArray = stringArray;
            this.intArray = intArray;
            this.nonCollection = nonCollection;
        }

        public List<String> getStringList() {
            return stringList;
        }

        public String[] getStringArray() {
            return stringArray;
        }

        public int[] getIntArray() {
            return intArray;
        }

        public Integer getNonCollection() {
            return nonCollection;
        }
    }

    @Test
    void testPojoFieldValidation() {
        // Valid: unique elements
        TestPojo valid = new TestPojo(Arrays.asList("a", "b", "c"), new String[]{"x", "y"}, new int[]{1, 2}, 5);
        Set<ConstraintViolation<TestPojo>> violations = validator.validate(valid);
        assertTrue(violations.isEmpty());

        // Invalid: duplicates
        TestPojo invalid = new TestPojo(Arrays.asList("a", "a"), new String[]{"x", "x"}, new int[]{1, 1}, null);
        violations = validator.validate(invalid);
        assertEquals(3, violations.size());

        // Valid: with nulls (multiple nulls ignored)
        TestPojo withNulls = new TestPojo(Arrays.asList("a", null, null, "b"), new String[]{"x", null, "y"}, new int[]{1, 0, 2}, null);
        violations = validator.validate(withNulls);
        assertTrue(violations.isEmpty());

        // Valid: null collection/array
        TestPojo nullValue = new TestPojo(null, null, null, null);
        violations = validator.validate(nullValue);
        assertTrue(violations.isEmpty());

        // Valid: empty
        TestPojo empty = new TestPojo(List.of(), new String[0], new int[0], null);
        violations = validator.validate(empty);
        assertTrue(violations.isEmpty());

        // Invalid: duplicates with nulls
        TestPojo dupWithNulls = new TestPojo(Arrays.asList("a", null, "a"), new String[]{"x", null, "x"}, new int[]{1, 1}, null);
        violations = validator.validate(dupWithNulls);
        assertEquals(3, violations.size());
    }

    @Test
    void testErrorMessage() {
        TestPojo invalid = new TestPojo(Arrays.asList("a", "a"), null, null, null);
        Set<ConstraintViolation<TestPojo>> violations = validator.validate(invalid);
        assertEquals(1, violations.size());

        ConstraintViolation<?> violation = violations.iterator().next();
        assertEquals("contains duplicates ([a, a])", violation.getMessage()); // Adjust based on actual resolved message
    }

    // Test for record types
    @Introspected
    record TestRecord(@UniqueElements List<String> stringList,
                      @UniqueElements String[] stringArray,
                      @UniqueElements int[] intArray,
                      @UniqueElements Integer nonCollection) {}

    @Test
    void testRecordValidation() {
        // Valid: unique
        TestRecord valid = new TestRecord(Arrays.asList("a", "b"), new String[]{"x", "y"}, new int[]{1, 2}, 5);
        Set<ConstraintViolation<TestRecord>> violations = validator.validate(valid);
        assertTrue(violations.isEmpty());

        // Invalid: duplicates
        TestRecord invalid = new TestRecord(Arrays.asList("a", "a"), new String[]{"x", "x"}, new int[]{1, 1}, null);
        violations = validator.validate(invalid);
        assertEquals(3, violations.size());

        // Valid: null
        TestRecord nullValue = new TestRecord(null, null, null, null);
        violations = validator.validate(nullValue);
        assertTrue(violations.isEmpty());
    }

    @Validated
    @Singleton
    static class TestService {
        public void testMethod(@UniqueElements List<String> stringList,
                               @UniqueElements String[] stringArray,
                               @UniqueElements int[] intArray,
                               @UniqueElements Integer nonCollection) {
        }
    }

    @Inject
    TestService service;

    @Test
    void testMethodArgumentValidation() {
        // Valid: no exception
        service.testMethod(Arrays.asList("a", "b"), new String[]{"x", "y"}, new int[]{1, 2}, 5);

        // Invalid: throws with 3 violations
        ConstraintViolationException ex = assertThrows(ConstraintViolationException.class,
                () -> service.testMethod(Arrays.asList("a", "a"), new String[]{"x", "x"}, new int[]{1, 1}, null));
        assertEquals(3, ex.getConstraintViolations().size());

        // Valid: null args
        service.testMethod(null, null, null, null);
    }
}
