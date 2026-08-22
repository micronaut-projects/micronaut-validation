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
package io.micronaut.validation.el.processor;

import io.micronaut.context.ApplicationContext;
import io.micronaut.validation.el.ElMessageInterpolator;
import io.micronaut.validation.validator.Validator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The end of the path: the validator interpolates the message of a violation with the expressions the
 * annotation processor compiled, without an interpreter on the classpath.
 */
class CompiledMessageInterpolationTest {

    @Test
    void theInterpolatorIsTheElOne() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertInstanceOf(ElMessageInterpolator.class, context.getBean(MessageInterpolator.class));
        }
    }

    @Test
    void theViolationMessagesAreInterpolatedWithTheCompiledExpressions() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<Book>> violations = validator.validate(new Book("a title that is far too long", 0.5));

            Set<String> messages = violations.stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toCollection(TreeSet::new));
            assertEquals(
                Set.of(
                    "0.50 is below 1.0",
                    "the title is 28 long, not between 1 and 8"
                ),
                messages
            );
        }
    }
}
