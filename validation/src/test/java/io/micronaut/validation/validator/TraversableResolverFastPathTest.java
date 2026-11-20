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
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.validator.resolver.CompositeTraversableResolver;
import jakarta.inject.Singleton;
import jakarta.validation.Path;
import jakarta.validation.TraversableResolver;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composite traversable resolver of an application without resolvers of its own traverses everything.
 */
class TraversableResolverFastPathTest {

    private static final String SPEC = "TraversableResolverFastPathTest";

    @Test
    void compositeWithoutResolversTakesTheFastPath() {
        try (ApplicationContext context = ApplicationContext.run()) {
            DefaultValidator validator = (DefaultValidator) context.getBean(Validator.class);
            assertTrue(validator.traversal().traversesAll());
        }
    }

    @Test
    void aSubclassOfTheCompositeIsAsked() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", SPEC))) {
            DefaultValidator validator = (DefaultValidator) context.getBean(Validator.class);
            assertFalse(validator.traversal().traversesAll());
            assertTrue(validator.validate(new Unreachable()).isEmpty());
        }
    }

    @Primary
    @Singleton
    @Replaces(CompositeTraversableResolver.class)
    @Requires(property = "spec.name", value = SPEC)
    static class NothingReachable extends CompositeTraversableResolver {
        NothingReachable() {
            super(List.<TraversableResolver>of());
        }

        @Override
        public boolean isReachable(Object traversableObject, Path.Node traversableProperty, Class<?> rootBeanType, Path pathToTraversableObject, ElementType elementType) {
            return false;
        }
    }

    @Introspected
    public static class Unreachable {
        @NotNull
        public String value;

        public String getValue() {
            return value;
        }
    }
}
