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
package io.micronaut.validation.validator.extractors;

import jakarta.validation.valueextraction.ValueExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A registry shared by validators answers each with the extractors as its own mapping of the type arguments
 * compares them: the mapping of the first caller is not cached for the others.
 */
class SharedValueExtractorRegistryTest {

    @Test
    void eachCallerIsAnsweredWithItsOwnMapping() {
        var registry = new DefaultValueExtractors();
        ValueExtractorDefinition<Base> base = definition(Base.class);
        ValueExtractorDefinition<Sub> sub = definition(Sub.class);
        registry.addValueExtractor(base);
        registry.addValueExtractor(sub);

        // both extract the same argument of the implementation: the more specific one alone applies
        List<ValueExtractorDefinition<Impl>> sameArgument = registry.findValueExtractors(Impl.class, definition -> 0);
        assertEquals(Set.of(sub), Set.copyOf(sameArgument));

        // another validator maps them onto different arguments: each applies to its own
        List<ValueExtractorDefinition<Impl>> differentArguments = registry.findValueExtractors(Impl.class,
            definition -> definition.containerType() == Sub.class ? 1 : 0);
        assertEquals(Set.of(sub, base), Set.copyOf(differentArguments));

        // and the first mapping is still answered as before
        assertEquals(Set.of(sub), Set.copyOf(registry.findValueExtractors(Impl.class, definition -> 0)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ValueExtractorDefinition<T> definition(Class<T> containerType) {
        ValueExtractor<T> extractor = (originalValue, receiver) -> { };
        return new ValueExtractorDefinition<>(containerType, (Class) Object.class, 0, false, extractor);
    }

    interface Base<T> {
    }

    interface Sub<T> extends Base<T> {
    }

    static final class Impl implements Sub<String> {
    }
}
