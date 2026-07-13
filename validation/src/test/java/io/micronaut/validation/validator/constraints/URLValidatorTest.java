/*
 * Copyright 2026 original authors
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
import io.micronaut.validation.annotation.URL;
import io.micronaut.validation.validator.Validator;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
class URLValidatorTest {

    @Inject
    Validator validator;

    @Introspected
    record TestUrl(
        @URL String url,
        @URL(protocol = "https", host = "micronaut.io", port = 443) String restrictedUrl,
        @URL(regexp = "https://.*", flags = Pattern.Flag.CASE_INSENSITIVE) String matchingUrl) {
    }

    @Test
    void validatesUrlsAndRestrictions() {
        assertTrue(validator.validate(new TestUrl(
            "https://micronaut.io",
            "https://micronaut.io:443/docs",
            "HTTPS://micronaut.io")).isEmpty());

        Set<ConstraintViolation<TestUrl>> violations = validator.validate(new TestUrl(
            "not a URL",
            "http://micronaut.io:443/docs",
            "http://micronaut.io"));
        assertEquals(3, violations.size());
    }

    @Test
    void permitsNullValuesButRejectsEmptyStrings() {
        assertTrue(validator.validate(new TestUrl(null, null, null)).isEmpty());
        assertFalse(validator.validate(new TestUrl("", null, null)).isEmpty());
    }

    @Test
    void validatesUsingAnnotationMetadata() {
        URLValidator urlValidator = new URLValidator();
        AnnotationValue<URL> annotation = AnnotationValue.builder(URL.class)
            .member("protocol", "https")
            .member("host", "micronaut.io")
            .member("port", 443)
            .build();

        assertTrue(urlValidator.isValid("https://micronaut.io:443", annotation, null));
        assertFalse(urlValidator.isValid("http://micronaut.io:443", annotation, null));
        assertFalse(urlValidator.isValid("https://micronaut.io", annotation, null));
    }

    @Test
    void usesTheDefaultMessage() {
        Set<ConstraintViolation<TestUrl>> violations = validator.validate(new TestUrl("invalid", null, null));
        assertEquals("must be a valid URL", violations.iterator().next().getMessage());
    }
}
