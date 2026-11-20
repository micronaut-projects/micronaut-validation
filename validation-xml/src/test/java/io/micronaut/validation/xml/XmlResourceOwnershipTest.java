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
package io.micronaut.validation.xml;

import io.micronaut.validation.validator.ReflectionSupport;
import jakarta.validation.ValidationException;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class XmlResourceOwnershipTest {
    @Test
    void closesEveryOwnedStreamOnceWhenTheFirstMappingIsMalformed() {
        TrackedStream malformed = new TrackedStream("<broken>", false);
        TrackedStream unread = new TrackedStream("<constraint-mappings version=\"3.1\"/>", false);
        assertThrows(ValidationException.class, () -> provider(malformed, unread));
        assertEquals(1, malformed.closed);
        assertEquals(1, unread.closed);
    }

    @Test
    void closesSuccessfulMappingsOnce() {
        TrackedStream stream = new TrackedStream("<constraint-mappings version=\"3.1\"/>", false);
        provider(stream);
        assertEquals(1, stream.closed);
    }

    @Test
    void preservesParseCauseAndSuppressedCleanupFailures() {
        TrackedStream malformed = new TrackedStream("<broken>", true);
        TrackedStream unread = new TrackedStream("<constraint-mappings version=\"3.1\"/>", true);
        ValidationException error = assertThrows(ValidationException.class, () -> provider(malformed, unread));
        assertNotNull(error.getCause());
        assertEquals(1, error.getSuppressed().length);
        assertEquals(1, error.getSuppressed()[0].getSuppressed().length);
        assertEquals(1, malformed.closed);
        assertEquals(1, unread.closed);
    }

    @Test
    void rejectsExternalGeneralAndParameterEntitiesWithoutReadingThem() {
        for (String declaration : List.of("<!ENTITY external SYSTEM 'file:///does-not-exist'>",
            "<!ENTITY % external SYSTEM 'https://example.invalid/evil.dtd'>%external;")) {
            TrackedStream stream = new TrackedStream("<!DOCTYPE constraint-mappings [" + declaration + "]>"
                + "<constraint-mappings version=\"3.1\"/>", false);
            ValidationException error = assertThrows(ValidationException.class, () -> provider(stream));
            assertNotNull(error.getCause());
            assertEquals(1, stream.closed);
        }
    }

    private static void provider(TrackedStream... streams) {
        XmlBeanIntrospector.of(ReflectionSupport.forClassLoader(XmlResourceOwnershipTest.class.getClassLoader()), new LinkedHashSet<>(List.of(streams)));
    }

    private static class TrackedStream extends ByteArrayInputStream {
        int closed;
        final boolean failClose;
        TrackedStream(String xml, boolean failClose) {
            super(xml.getBytes(StandardCharsets.UTF_8));
            this.failClose = failClose;
        }
        @Override public void close() throws IOException {
            closed++;
            if (failClose) { throw new IOException("cleanup failure"); }
            super.close();
        }
    }
}
