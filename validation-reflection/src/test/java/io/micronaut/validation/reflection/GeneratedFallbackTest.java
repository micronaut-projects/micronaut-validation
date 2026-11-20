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
package io.micronaut.validation.reflection;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.validation.validator.DefaultValidatorConfiguration;
import io.micronaut.validation.validator.ReflectionSupport;
import io.micronaut.validation.validator.ValidationAnnotationUtil;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedFallbackTest {
    @Test
    void ordinaryCompiledAttributesAndStandardConstraintsDoNotRequestConcreteAnnotations() {
        var support = new ReflectionValidationSupport();
        assertNull(support.annotationAttributes(NotNull.class, AnnotationValue.builder(NotNull.class).build()));
        var occurrence = BeanIntrospector.SHARED.getIntrospection(PlainBean.class).getRequiredProperty("value", String.class)
            .getAnnotationMetadata().getAnnotation(Plain.class);
        assertNotNull(occurrence);
        assertTrue(occurrence.booleanValue(ValidationAnnotationUtil.DEFINITION_CHECKED).orElse(false));
        assertNull(support.annotationAttributes(Plain.class, occurrence));
    }

    @Test
    void genericSignaturesUseTheConfiguredIntrospector() {
        var lookups = new AtomicInteger();
        var introspector = (BeanIntrospector) Proxy.newProxyInstance(BeanIntrospector.class.getClassLoader(),
            new Class<?>[]{BeanIntrospector.class}, (proxy, method, arguments) -> {
                lookups.incrementAndGet();
                return method.invoke(BeanIntrospector.SHARED, arguments);
            });
        ReflectionSupport support = new ReflectionValidationSupport().withIntrospector(introspector);
        support.genericSuperArgument(FlagValidator.class, jakarta.validation.ConstraintValidator.class);
        assertTrue(lookups.get() > 0);
    }

    @Test
    @SuppressWarnings("removal")
    void deprecatedHelperRetainsParameterizedWildcardAndGenericArrayErasure() throws Exception {
        assertSame(List.class, DefaultValidatorConfiguration.getClassFromType(Signatures.class.getDeclaredField("strings").getGenericType()));
        var list = (ParameterizedType) Signatures.class.getDeclaredField("wildcard").getGenericType();
        assertSame(Number.class, DefaultValidatorConfiguration.getClassFromType(list.getActualTypeArguments()[0]));
        assertSame(Object[].class, DefaultValidatorConfiguration.getClassFromType(Signatures.class.getDeclaredField("array").getGenericType()));
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
    @Constraint(validatedBy = {})
    @NotNull
    @interface Plain {
        String message() default "plain";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
        String label() default "generated";
    }

    @Introspected
    record PlainBean(@Plain String value) { }

    static class Signatures<T> {
        List<String> strings;
        List<? extends Number> wildcard;
        T[] array;
    }
}
