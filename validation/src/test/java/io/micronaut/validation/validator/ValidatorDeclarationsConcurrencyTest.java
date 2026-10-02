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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospector;
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.ConvertGroup;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorDeclarationsConcurrencyTest {
    @Test
    void constraintChecksPublishOnlyCompletedSuccessAndFailuresAreRetried() throws Exception {
        assertConcurrentFailure("checkConstraintDefinition", declarations -> declarations.checkConstraintDefinition(
            NotNull.class, AnnotationValue.builder(NotNull.class).build()));
    }

    @Test
    void beanChecksPublishOnlyCompletedSuccessAndFailuresAreRetried() throws Exception {
        var introspection = BeanIntrospector.SHARED.getIntrospection(ConvertedBean.class);
        assertConcurrentFailure("isGroupSequence", declarations -> declarations.checkBeanDeclarations(introspection));
    }

    private static void assertConcurrentFailure(String checkedMethod, Consumer<ValidatorDeclarations> check) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        var support = (ReflectionSupport) Proxy.newProxyInstance(ReflectionSupport.class.getClassLoader(),
            new Class<?>[]{ReflectionSupport.class}, (proxy, method, arguments) -> {
                if (method.getName().equals(checkedMethod)) {
                    attempts.incrementAndGet();
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    throw new ConstraintDefinitionException("invalid declaration");
                }
                return method.invoke(new CompileTimeSupport(), arguments);
            });
        var declarations = new ValidatorDeclarations(BeanIntrospector.SHARED, true, List.of(), support);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> check.accept(declarations));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                secondStarted.countDown();
                check.accept(declarations);
            });
            try {
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assertInstanceOf(ConstraintDefinitionException.class,
                assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS)).getCause());
            assertInstanceOf(ConstraintDefinitionException.class,
                assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(2, attempts.get());
        }
    }

    interface Converted { }

    @Introspected
    record ConvertedBean(@Valid @ConvertGroup(to = Converted.class) Object value) { }
}
