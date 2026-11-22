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

import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.validator.DefaultValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The constraints of a container element are validated through the extractor of the declared container type, and
 * the cascade into the element follows the runtime type of the container, as the reference implementation does: a
 * declared iterable holding a list cascades into its elements by index even when the element is constrained too.
 */
class ConstrainedCascadedElementRuntimeExtractorTest {

    @Test
    void theCascadeOfAConstrainedElementFollowsTheRuntimeTypeOfTheContainer() {
        try (var factory = new DefaultValidatorFactory()) {
            Set<String> paths = new TreeSet<>();
            for (ConstraintViolation<Holder> violation : factory.getValidator().validate(new Holder())) {
                paths.add(violation.getPropertyPath().toString() + " " + violation.getMessage());
            }
            assertEquals(Set.of(
                "constrained[1].name must not be null",
                "constrained[].<iterable element> must not be null",
                "cascadedOnly[1].name must not be null"
            ), paths);
        }
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static final class Holder {
        public Iterable<@NotNull @Valid Child> constrained = new ArrayList<>(Arrays.asList(new Child("a"), new Child(null), null));
        public Collection<@Valid Child> cascadedOnly = new ArrayList<>(Arrays.asList(new Child("a"), new Child(null)));
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static final class Child {
        @NotNull public String name;

        Child(String name) {
            this.name = name;
        }
    }
}
