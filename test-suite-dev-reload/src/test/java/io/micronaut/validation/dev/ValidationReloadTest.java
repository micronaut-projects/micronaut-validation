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
package io.micronaut.validation.dev;

import io.micronaut.context.ApplicationContext;
import io.micronaut.dev.tck.ReloadHarness;
import io.micronaut.dev.tck.ReloadTck;
import io.micronaut.validation.validator.Validator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorRegistry;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs an application through the development runtime and edits the constraints of a validated
 * type: the next generation validates with the new constraints, finds the application's own
 * validator again, and nothing of validation keeps a retired generation reachable.
 */
class ValidationReloadTest {

    private static final String PERSON = """
        package example;

        @io.micronaut.core.annotation.Introspected
        public record Person(@jakarta.validation.constraints.Size(max = %d) String name,
                             @example.Even(groups = example.Checks.class) int age) {
        }
        """;

    @TempDir
    Path project;

    @Test
    void constraintsFollowAReloadAndLeaveNoRetiredGenerationReachable() {
        try (ReloadHarness harness = ReloadHarness.inDirectory(project)) {
            harness.source("example.Checks", """
                package example;

                public interface Checks {
                }
                """);
            // a group sequence, which validation resolves once per group class and keeps
            harness.source("example.Ordered", """
                package example;

                @io.micronaut.core.annotation.Introspected
                @jakarta.validation.GroupSequence({jakarta.validation.groups.Default.class, example.Checks.class})
                public interface Ordered {
                }
                """);
            harness.source("example.Even", """
                package example;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.RUNTIME)
                @jakarta.validation.Constraint(validatedBy = {})
                public @interface Even {
                    String message() default "must be even";
                    Class<?>[] groups() default {};
                    Class<? extends jakarta.validation.Payload>[] payload() default {};
                }
                """);
            harness.source("example.EvenValidator", """
                package example;

                @jakarta.inject.Singleton
                public class EvenValidator implements io.micronaut.validation.validator.constraints.ConstraintValidator<Even, Integer> {
                    @Override
                    public boolean isValid(Integer value, io.micronaut.core.annotation.AnnotationValue<Even> annotation,
                                           io.micronaut.validation.validator.constraints.ConstraintValidatorContext context) {
                        return value == null || value % 2 == 0;
                    }
                }
                """);
            harness.source("example.Person", PERSON.formatted(5));
            harness.start();

            assertEquals(List.of(), violations(harness.context(), "Alice", 42));
            assertEquals(List.of("age"), violations(harness.context(), "Alice", 41));
            ReloadTck.assertFollowsReload(harness, ValidationReloadTest::evenValidator);

            // the constraint on the name tightens
            harness.source("example.Person", PERSON.formatted(3));
            harness.reload();

            assertEquals(List.of("name"), violations(harness.context(), "Alice", 42));
            assertEquals(List.of("name"), violations(harness.context(), "Alice", 41));
            assertEquals(List.of("age"), violations(harness.context(), "Bob", 41));
            ReloadTck.assertFollowsReload(harness, ValidationReloadTest::evenValidator);

            // the group sequences are kept on the group classes, not in a static map
            ReloadTck.assertRetiredGenerationsCollected(harness);
        }
    }

    /**
     * The properties in violation when a person is validated with the ordered group sequence, which
     * stops at the first group that fails.
     */
    private static List<String> violations(ApplicationContext context, String name, int age) {
        try {
            Class<?> person = type(context, "example.Person");
            Object instance = person.getConstructor(String.class, int.class).newInstance(name, age);
            Set<ConstraintViolation<Object>> violations = context.getBean(Validator.class).validate(instance, type(context, "example.Ordered"));
            return violations.stream().map(violation -> violation.getPropertyPath().toString()).sorted().toList();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot create a person", e);
        }
    }

    private static Object evenValidator(ApplicationContext context) {
        return context.getBean(ConstraintValidatorRegistry.class)
            .findConstraintValidator(type(context, "example.Even"), Integer.class)
            .orElseThrow(() -> new AssertionError("No validator for @Even"));
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> type(ApplicationContext context, String className) {
        try {
            return (Class<T>) Class.forName(className, true, context.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError(className + " is not in the application", e);
        }
    }
}
