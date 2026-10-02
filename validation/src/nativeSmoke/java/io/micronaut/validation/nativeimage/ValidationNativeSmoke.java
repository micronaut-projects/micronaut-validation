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
package io.micronaut.validation.nativeimage;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.validation.validator.DefaultValidatorFactory;

import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Exercises generated access, generic containers, and rejection of private-field reflection.
 */
public final class ValidationNativeSmoke {
    private ValidationNativeSmoke() { }

    /**
     * Runs the native validation assertions.
     *
     * @param arguments Unused command arguments
     */
    public static void main(String[] arguments) {
        try (var factory = new DefaultValidatorFactory()) {
            var validator = factory.getValidator();
            if (validator.validate(new Generated()).size() != 2) {
                throw new AssertionError("Generated declarations were lost");
            }
            try {
                validator.validate(new Annotated());
                throw new AssertionError("Private field reflection must require the companion module");
            } catch (jakarta.validation.ValidationException expected) {
                // Native registration does not authorize reflection in the default module.
            }
            if (validator.validate(new Component(null)).size() != 1
                || validator.validate(new Property()).size() != 1) {
                throw new AssertionError(
                        "Generated accessor metadata was lost");
            }
        }
        System.out.println("Native validation smoke passed");
    }

    /** Declarations using generated access exclusively. */
    @Introspected(
            accessKind = Introspected.AccessKind.FIELD,
            visibility = Introspected.Visibility.ANY)
    public static final class Generated {
        @NotNull @Nullable String value;
        List<@NotNull String> items = java.util.Arrays.asList("valid", null);
    }

    /** Field-specific reflective access registered during processing. */
    @Introspected(
            accessKind = Introspected.AccessKind.FIELD,
            visibility = Introspected.Visibility.ANY)
    public static final class Annotated {
        @NotNull @ReflectiveAccess private @Nullable String value;
    }

    /**
     * Record components use generated accessors.
     *
     * @param value The constrained value
     */
    @Introspected
    public record Component(@NotNull @Nullable String value) { }

    /** Backing field constraints use the generated property getter. */
    @Introspected
    public static final class Property {
        @NotNull private @Nullable String value;

        /** @return The constrained property */
        public @Nullable String getValue() {
            return value;
        }
    }
}
