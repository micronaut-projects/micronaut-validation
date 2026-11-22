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
package io.micronaut.validation.validator.messages;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.server.util.locale.HttpLocaleResolver;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP locale resolver reads a type of the optional HTTP server: it requires the class, so that the native image
 * feature of core leaves out its definition where the HTTP server is absent, rather than initialising a definition
 * that failed to load.
 */
class HttpInterpolatorLocaleResolverRequirementsTest {

    @Test
    void theResolverRequiresTheClassOfTheHttpServer() {
        try (ApplicationContext context = ApplicationContext.builder().start()) {
            var reference = context.getBeanDefinitionReferences().stream()
                .filter(r -> r.getBeanType().equals(HttpInterpolatorLocaleResolver.class))
                .findFirst()
                .orElseThrow();
            boolean requiresClass = reference.getAnnotationMetadata().getAnnotationValuesByType(Requires.class).stream()
                .anyMatch(requires -> Arrays.asList(requires.stringValues("classes")).contains(HttpLocaleResolver.class.getName()));
            assertTrue(requiresClass, reference.getAnnotationMetadata().getAnnotationValuesByType(Requires.class).toString());
        }
    }
}
