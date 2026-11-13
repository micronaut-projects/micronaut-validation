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
package io.micronaut.validation.validator.reactive;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Executable;
import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The values a reactive parameter emits are validated as the call validates the parameter: in the groups its
 * conversion converts to, and reported by the exception the validator is configured to create.
 */
class ReactiveParameterValidationTest {

    @Test
    void aPublishedValueIsReportedAsTheValidatorIsConfigured() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("micronaut.validator.prepend-property-path", false))) {
            var service = context.getBean(Service.class);
            var e = assertThrows(ConstraintViolationException.class,
                () -> Flux.from(service.titles(Flux.just(new Item(null, "")))).blockLast());
            ConstraintViolation<?> violation = e.getConstraintViolations().iterator().next();
            assertEquals(violation.getMessage(), e.getMessage());
        }
    }

    @Test
    void aCompletedValueIsValidatedInTheConvertedGroups() {
        try (ApplicationContext context = ApplicationContext.run()) {
            var service = context.getBean(Service.class);
            var e = assertThrows(CompletionException.class,
                () -> service.stage(CompletableFuture.completedFuture(new Item(null, "title"))).toCompletableFuture().join());
            var violations = assertInstanceOf(ConstraintViolationException.class, e.getCause()).getConstraintViolations();
            assertEquals(1, violations.size());
            assertEquals("name", lastNode(violations.iterator().next()));
        }
    }

    @Test
    void aPublishedValueIsValidatedInTheConvertedGroups() {
        try (ApplicationContext context = ApplicationContext.run()) {
            var service = context.getBean(Service.class);
            var e = assertThrows(ConstraintViolationException.class,
                () -> Mono.from(service.published(Mono.just(new Item(null, "title")))).block());
            assertEquals(1, e.getConstraintViolations().size());
            assertEquals("name", lastNode(e.getConstraintViolations().iterator().next()));
        }
    }

    private static String lastNode(ConstraintViolation<?> violation) {
        String name = null;
        for (var node : violation.getPropertyPath()) {
            name = node.getName();
        }
        return name;
    }

    interface Strict {
    }

    @Introspected
    record Item(@NotNull(groups = Strict.class) @Nullable String name, @NotBlank String title) {
    }

    @Singleton
    static class Service {

        @Executable
        Publisher<String> titles(Publisher<@Valid Item> items) {
            return Flux.from(items).map(Item::title);
        }

        @Executable
        CompletionStage<String> stage(@Valid @ConvertGroup(from = Default.class, to = Strict.class) CompletionStage<@Valid Item> item) {
            return item.thenApply(Item::title);
        }

        @Executable
        Publisher<String> published(@Valid @ConvertGroup(from = Default.class, to = Strict.class) Mono<@Valid Item> item) {
            return item.map(Item::title);
        }
    }
}
