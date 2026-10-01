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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.validation.validator.metadata.GeneratedAnnotationFactories;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import org.junit.jupiter.api.Test;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedAnnotationContractTest {
    @Test
    void concreteAnnotationsImplementEqualityHashingDefaultsAndDefensiveArrays() throws Exception {
        var descriptor = Validator.getInstance().getConstraintsForClass(Bean.class).getConstraintsForProperty("value")
            .getConstraintDescriptors().iterator().next();
        Contract generated = (Contract) descriptor.getAnnotation();
        Contract reference = Bean.class.getDeclaredField("value").getAnnotation(Contract.class);
        assertEquals(reference, generated);
        assertEquals(generated, reference);
        assertEquals(reference.hashCode(), generated.hashCode());
        assertEquals(Contract.class, generated.annotationType());
        assertEquals("contract", generated.message());
        assertEquals("nested", generated.nested().value());
        assertEquals(2, generated.nestedArray().length);
        assertEquals("second", generated.nestedArray()[1].value());
        Nested[] nested = generated.nestedArray();
        nested[0] = generated.nested();
        assertEquals("first", generated.nestedArray()[0].value());
        int[] mutated = generated.numbers();
        mutated[0] = 99;
        assertArrayEquals(new int[]{1, 2}, generated.numbers());
        assertFalse(java.lang.reflect.Proxy.isProxyClass(generated.getClass()));
    }

    @Test
    void initializedAnnotationSnapshotsDoNotRetainCallerOwnedArrays() {
        int[] numbers = {3, 4};
        Contract annotation = GeneratedAnnotationFactories.create(Contract.class, AnnotationValue.builder(Contract.class)
            .member("message", "contract").member("numbers", numbers).member("groups", new Class<?>[0])
            .member("payload", new Class<?>[0])
            .member("nested", AnnotationValue.builder(Nested.class).value("nested").build())
            .member("nestedArray", new AnnotationValue<?>[]{AnnotationValue.builder(Nested.class).value("first").build()}).build());
        numbers[0] = 99;
        assertArrayEquals(new int[]{3, 4}, annotation.numbers());
    }

    @Test
    void defaultsAreEmbeddedEvenWhenTheCallerSuppliesNoDefaultRegistry() throws Exception {
        Contract generated = GeneratedAnnotationFactories.create(Contract.class, new AnnotationValue<>(Contract.class.getName()));
        Contract reference = Bean.class.getDeclaredField("value").getAnnotation(Contract.class);
        assertEquals(reference, generated);
        assertEquals(reference.hashCode(), generated.hashCode());
        assertEquals("", generated.empty());
        assertEquals(jakarta.validation.constraints.Pattern.Flag.DOTALL, generated.flag());
        assertArrayEquals(new jakarta.validation.constraints.Pattern.Flag[]{jakarta.validation.constraints.Pattern.Flag.CASE_INSENSITIVE}, generated.flags());
        assertArrayEquals(new Class<?>[]{String.class, Integer.class}, generated.types());
        assertArrayEquals(new byte[]{1, 2}, generated.bytes());
        assertArrayEquals(new char[]{'a', 'é'}, generated.characters());
        assertArrayEquals(new boolean[]{true, false}, generated.switches());
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Nested { String value(); }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @Constraint(validatedBy = {})
    public @interface Contract {
        String message() default "contract";
        int[] numbers() default {1, 2};
        String empty() default "";
        byte[] bytes() default {1, 2};
        char[] characters() default {'a', 'é'};
        boolean[] switches() default {true, false};
        Class<?>[] types() default {String.class, Integer.class};
        jakarta.validation.constraints.Pattern.Flag flag() default jakarta.validation.constraints.Pattern.Flag.DOTALL;
        jakarta.validation.constraints.Pattern.Flag[] flags() default {jakarta.validation.constraints.Pattern.Flag.CASE_INSENSITIVE};
        Nested nested() default @Nested("nested");
        Nested[] nestedArray() default {@Nested("first"), @Nested("second")};
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static class Bean {
        @Contract public String value;
    }
}
