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

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.UnwrapByDefault;
import jakarta.validation.valueextraction.ValueExtractor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An extractor unwrapping by default reads the constraints declared on the container: the extractors of the
 * other type arguments still validate the constraints declared on them.
 */
class UnwrapByDefaultExtractorTest {
    static ApplicationContext ctx;
    static Validator validator;

    @BeforeAll
    static void start() {
        ctx = ApplicationContext.run();
        validator = ctx.getBean(Validator.class);
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    public record Pair<L, R>(L left, R right) { }

    @Singleton
    @UnwrapByDefault
    public static class PairLeftExtractor implements ValueExtractor<Pair<@ExtractedValue ?, ?>> {
        @Override
        public void extractValues(Pair<?, ?> originalValue, ValueReceiver receiver) {
            receiver.value(null, originalValue.left());
        }
    }

    @Singleton
    public static class PairRightExtractor implements ValueExtractor<Pair<?, @ExtractedValue ?>> {
        @Override
        public void extractValues(Pair<?, ?> originalValue, ValueReceiver receiver) {
            receiver.value("right", originalValue.right());
        }
    }

    @Introspected
    public record RightConstrained(Pair<String, @NotNull String> pair) { }

    @Introspected
    public record ContainerConstrained(@NotNull Pair<String, String> pair) { }

    private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void theOtherTypeArgumentKeepsItsConstraints() {
        var violations = validator.validate(new RightConstrained(new Pair<>("x", null)));
        assertEquals(1, violations.size(), violations.toString());
        var nodes = new java.util.ArrayList<jakarta.validation.Path.Node>();
        violations.iterator().next().getPropertyPath().forEach(nodes::add);
        assertEquals("pair", nodes.get(0).getName());
        assertEquals("right", nodes.get(nodes.size() - 1).getName());
        assertEquals(jakarta.validation.ElementKind.CONTAINER_ELEMENT, nodes.get(nodes.size() - 1).getKind());
    }

    @Test
    void aConstraintOnTheContainerUnwrapsTheDefaultValue() {
        var violations = validator.validate(new ContainerConstrained(new Pair<>(null, "x")));
        assertEquals(1, violations.size(), violations.toString());
        assertEquals(Set.of("pair"), paths(violations));
    }
}
