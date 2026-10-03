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
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospectionReference;
import io.micronaut.core.beans.BeanIntrospector;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Without the reflection module the validator reads the generated introspections only: an introspector may
 * also serve what a fallback describes reflectively, which is not for the default validator to validate through.
 */
class GeneratedIntrospectionsOnlyTest {

    @Test
    void anIntrospectionNoProcessorGeneratedIsNotRead() {
        var configuration = new DefaultValidatorConfiguration();
        configuration.setBeanIntrospector(new ForeignIntrospector());
        try (var factory = new DefaultValidatorFactory(configuration)) {
            var validator = factory.getValidator();
            assertFalse(validator.getConstraintsForClass(Described.class).isBeanConstrained());
            // the type is one without an introspection, which validating it reports
            assertThrows(ValidationException.class, () -> validator.validate(new Described(null)));
            // a type the processors described is read as before through the same introspector
            assertTrue(validator.getConstraintsForClass(Generated.class).isBeanConstrained());
            assertFalse(validator.validate(new Generated(null)).isEmpty());
        }
    }

    @Test
    void theGeneratedIntrospectionOfTheSameTypeIsRead() {
        try (var factory = new DefaultValidatorFactory()) {
            assertTrue(factory.getValidator().getConstraintsForClass(Described.class).isBeanConstrained());
        }
    }

    @Introspected
    record Described(@NotNull @Nullable String value) { }

    @Introspected
    record Generated(@NotNull @Nullable String value) { }

    /** Serves one type the way a fallback would: by an introspection that is not a generated one. */
    private static final class ForeignIntrospector implements BeanIntrospector {
        @Override
        public Collection<BeanIntrospection<Object>> findIntrospections(Predicate<? super BeanIntrospectionReference<?>> filter) {
            return SHARED.findIntrospections(filter);
        }

        @Override
        public Collection<Class<?>> findIntrospectedTypes(Predicate<? super BeanIntrospectionReference<?>> filter) {
            return SHARED.findIntrospectedTypes(filter);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<BeanIntrospection<T>> findIntrospection(Class<T> beanType) {
            Optional<BeanIntrospection<T>> generated = SHARED.findIntrospection(beanType);
            if (beanType != Described.class) {
                return generated;
            }
            BeanIntrospection<T> target = generated.orElseThrow();
            return Optional.of((BeanIntrospection<T>) Proxy.newProxyInstance(
                beanType.getClassLoader(), new Class<?>[] {BeanIntrospection.class},
                (proxy, method, arguments) -> method.invoke(target, arguments)));
        }
    }
}
