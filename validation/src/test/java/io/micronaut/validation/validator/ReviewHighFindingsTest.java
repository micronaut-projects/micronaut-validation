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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.GroupSequence;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

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

    private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    public interface First { }

    public interface Second { }

    public interface Third { }

    public interface Other { }

    @GroupSequence({First.class, Second.class})
    public interface FirstThenSecond { }

    @GroupSequence({First.class, Third.class})
    public interface FirstThenThird { }

    @GroupSequence({Default.class, Second.class})
    public interface DefaultThenSecond { }

    @Introspected
    public record Sequenced(@NotNull String plain,
                            @NotNull(groups = First.class) String first,
                            @NotNull(groups = Second.class) String second,
                            @NotNull(groups = Third.class) String third,
                            @NotNull(groups = Other.class) String other) { }

    // an interface group sequence may contain the default group, Jakarta Validation 5.4.2

    @Test
    void anInterfaceSequenceMayContainTheDefaultGroup() {
        try (var factory = new DefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertEquals(Set.of("plain"), paths(validator.validate(new Sequenced(null, "x", null, "x", "x"), DefaultThenSecond.class)));
            assertEquals(Set.of("second"), paths(validator.validate(new Sequenced("x", "x", null, "x", "x"), DefaultThenSecond.class)));
        }
    }

    // a failed step of a sequence ends that sequence only, Jakarta Validation 5.4.2

    @Test
    void aFailedSequenceStepDoesNotStopTheGroupsRequestedAfterIt() {
        try (var factory = new DefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertEquals(Set.of("first", "other"),
                paths(validator.validate(new Sequenced("x", null, null, "x", null), FirstThenSecond.class, Other.class)));
        }
    }

    @Test
    void aFailedSequenceStepDoesNotStopAnotherSequence() {
        try (var factory = new DefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertEquals(Set.of("second", "third"),
                paths(validator.validate(new Sequenced("x", "x", null, null, "x"), FirstThenSecond.class, FirstThenThird.class)));
        }
    }

    // a group converted to a sequence containing it, Jakarta Validation 5.4.5

    @GroupSequence({First.class, Second.class})
    public interface ConvertedSequence { }

    @Introspected
    public record Child(@NotNull(groups = First.class) String first,
                        @NotNull(groups = Second.class) String second) { }

    @Introspected
    public record Parent(@Valid @ConvertGroup(from = First.class, to = ConvertedSequence.class) Child child) { }

    @Test
    void aGroupConvertedToASequenceContainingItIsNoCycle() {
        try (var factory = new DefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertEquals(Set.of("child.first"), paths(validator.validate(new Parent(new Child(null, null)), First.class)));
            assertEquals(Set.of("child.second"), paths(validator.validate(new Parent(new Child("x", null)), First.class)));
        }
    }
}
