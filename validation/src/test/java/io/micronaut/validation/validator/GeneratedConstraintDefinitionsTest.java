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
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.Payload;
import jakarta.validation.OverridesAttribute;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.ValueExtractor;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedConstraintDefinitionsTest {
    @Test
    void strictDefinitionsAreCheckedWithoutTheReflectionCompanion() {
        var configuration = new DefaultValidatorConfiguration().setStrictConstraintDefinitions(true);
        try (var factory = new DefaultValidatorFactory(configuration)) {
            assertEquals(1, factory.getValidator().validate(new ValidBean(null)).size());
            assertThrows(ConstraintDefinitionException.class, () -> factory.getValidator().validate(new MissingCompositionBean("a")));
            for (int i = 0; i < 2; i++) {
                assertThrows(ConstraintDefinitionException.class, () -> factory.getValidator().validate(new InvalidBean(null)));
            }
        }
    }

    @Test
    void nestedCompositionOverridesUseBinaryAnnotationNames() {
        var configuration = new DefaultValidatorConfiguration().setStrictConstraintDefinitions(true);
        try (var factory = new DefaultValidatorFactory(configuration)) {
            assertEquals(1, factory.getValidator().validate(new NestedCodeBean("a")).size());
            assertEquals(0, factory.getValidator().validate(new NestedCodeBean("abcd")).size());
        }
    }

    @Test
    void registeredExtractorInstancesUseTheirGeneratedSignatures() {
        var configuration = new DefaultValidatorConfiguration();
        assertDoesNotThrow(() -> configuration.addValueExtractor(new BoxExtractor()));
        try (var factory = new DefaultValidatorFactory(configuration)) {
            assertEquals(1, factory.getValidator().validate(new BoxBean(new Box<>(null))).size());
        }
    }

    @Test
    @SuppressWarnings("removal")
    void deprecatedErasureHelperErasesSignaturesWithoutTheCompanion() throws Exception {
        assertSame(String.class, DefaultValidatorConfiguration.getClassFromType(String.class));
        var type = Signatures.class.getDeclaredField("strings").getGenericType();
        assertSame(java.util.List.class, DefaultValidatorConfiguration.getClassFromType(type));
    }

    @Introspected
    record ValidBean(@NotNull String value) { }

    @Introspected
    record InvalidBean(@Invalid String value) { }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
    @Constraint(validatedBy = {})
    @NotNull
    @interface Invalid {
        String message() default "invalid";
        Class<?>[] groups() default {Basic.class};
        Class<? extends Payload>[] payload() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.ANNOTATION_TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
    @Constraint(validatedBy = {})
    @Size(min = 1)
    @interface Code {
        String message() default "code";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
        @OverridesAttribute(constraint = Size.class, name = "min")
        int min() default 1;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
    @Constraint(validatedBy = {})
    @Code
    @interface NestedCode {
        String message() default "nested";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
        @OverridesAttribute(constraint = Code.class, name = "min")
        int min() default 2;
    }

    @Introspected
    record NestedCodeBean(@NestedCode String value) { }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
    @Constraint(validatedBy = {})
    @interface MissingComposition {
        String message() default "missing";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
        @OverridesAttribute(constraint = Size.class, name = "min")
        int min() default 2;
    }

    @Introspected
    record MissingCompositionBean(@MissingComposition String value) { }

    interface Basic { }
    record Box<T>(T value) { }

    static class BoxExtractor implements ValueExtractor<Box<@ExtractedValue ?>> {
        @Override
        public void extractValues(Box<?> box, ValueReceiver receiver) {
            receiver.value(null, box.value());
        }
    }

    @Introspected
    record BoxBean(Box<@NotNull String> box) { }

    static class Signatures {
        List<String> strings;
    }
}
