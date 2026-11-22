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

import io.micronaut.inject.ExecutableMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A wrapper built per call for the same method is the same key of the plans of the validator.
 */
class CallerSuppliedExecutableTest {

    @Test
    @SuppressWarnings("unchecked")
    void wrappersOfTheSameMethodAreEqual() throws NoSuchMethodException {
        ExecutableMethod<Object, Object> delegate = (ExecutableMethod<Object, Object>) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{ExecutableMethod.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> null;
            });
        Method method = Object.class.getMethod("toString");
        var first = new CallerSuppliedExecutable<>(delegate, method);
        var second = new CallerSuppliedExecutable<>(delegate, method);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, new CallerSuppliedExecutable<>(delegate, Object.class.getMethod("hashCode")));
    }
}
