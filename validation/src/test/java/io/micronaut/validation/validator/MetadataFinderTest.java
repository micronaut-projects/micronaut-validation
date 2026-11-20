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
import jakarta.validation.GroupSequence;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The constraint finders of the metadata API, Jakarta Validation 7.2: a group sequence matches the constraints of
 * the groups it names, and a finder answers {@code hasConstraints()} for the constraints it finds.
 */
class MetadataFinderTest {

    public interface Basic { }

    public interface Detail { }

    public interface Audit { }

    @GroupSequence({Basic.class, Detail.class})
    public interface Ordered { }

    @Introspected
    public record Item(@NotNull(groups = Basic.class) String name, @NotNull String plain) { }

    @Test
    void aGroupSequenceMatchesTheConstraintsOfItsGroups() {
        try (var factory = new DefaultValidatorFactory()) {
            var property = factory.getValidator().getConstraintsForClass(Item.class).getConstraintsForProperty("name");
            assertEquals(1, property.findConstraints().unorderedAndMatchingGroups(Ordered.class).getConstraintDescriptors().size());
        }
    }

    @Test
    void aFilteredPropertyFinderHasTheConstraintsItFinds() {
        try (var factory = new DefaultValidatorFactory()) {
            var finder = factory.getValidator().getConstraintsForClass(Item.class).getConstraintsForProperty("plain")
                .findConstraints().unorderedAndMatchingGroups(Audit.class);
            assertEquals(0, finder.getConstraintDescriptors().size());
            assertFalse(finder.hasConstraints());
        }
    }
}
