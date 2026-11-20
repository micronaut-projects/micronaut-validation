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
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The high findings of the review of 2026-11-20.
 */
class ReviewHighFindingsTest {

    // a method of an interface validated on two implementations declaring different return constraints

    @Introspected
    public interface Lookup {
        @Size(max = 100) String find();
    }

    @Introspected
    public static class ShortLookup implements Lookup {
        @Override
        @Size(min = 1)
        public String find() {
            return "ab";
        }
    }

    @Introspected
    public static class LongLookup implements Lookup {
        @Override
        @Size(min = 5)
        public String find() {
            return "ab";
        }
    }

    @Test
    void anInterfaceMethodUsesTheConstraintsOfEachImplementation() throws Exception {
        try (var factory = new DefaultValidatorFactory()) {
            var executables = factory.getValidator().forExecutables();
            var method = Lookup.class.getMethod("find");
            assertEquals(0, executables.validateReturnValue(new ShortLookup(), method, "ab").size());
            assertEquals(1, executables.validateReturnValue(new LongLookup(), method, "ab").size());
        }
    }
}
