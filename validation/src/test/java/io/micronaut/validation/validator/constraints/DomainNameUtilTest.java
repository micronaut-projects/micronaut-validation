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
package io.micronaut.validation.validator.constraints;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainNameUtilTest {

    @Test
    void longDomainShortenedByTheConversionDoesNotOverflow() {
        String domain = "a" + "­".repeat(10000) + ".com";
        assertTrue(DomainNameUtil.isValidEmailDomainAddress(domain));
    }

    @Test
    void internationalAndInvalidDomains() {
        assertTrue(DomainNameUtil.isValidEmailDomainAddress("bücher.example"));
        assertTrue(DomainNameUtil.isValidEmailDomainAddress("[127.0.0.1]"));
        assertTrue(DomainNameUtil.isValidEmailDomainAddress("[IPv6:::1]"));
        assertFalse(DomainNameUtil.isValidEmailDomainAddress("a..com"));
        assertFalse(DomainNameUtil.isValidEmailDomainAddress("-a.com"));
        assertFalse(DomainNameUtil.isValidEmailDomainAddress("a b.com"));
    }
}
