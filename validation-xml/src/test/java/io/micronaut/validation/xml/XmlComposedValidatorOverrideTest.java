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
package io.micronaut.validation.xml;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.bootstrap.MicronautValidatorConfiguration;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.metadata.ConstraintDescriptor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The validators XML defines for a constraint apply where another constraint composes it, Jakarta Validation 9.3.
 */
class XmlComposedValidatorOverrideTest {

    private static final String MAPPING = """
        <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
            <constraint-definition annotation="io.micronaut.validation.xml.XmlComposedValidatorOverrideTest$Code">
                <validated-by include-existing-validators="false">
                    <value>io.micronaut.validation.xml.XmlComposedValidatorOverrideTest$StrictCodeValidator</value>
                </validated-by>
            </constraint-definition>
        </constraint-mappings>
        """;

    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = LenientCodeValidator.class)
    public @interface Code {
        String message() default "code";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = {})
    @Code
    public @interface ProductCode {
        String message() default "product code";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @Introspected
    public static class LenientCodeValidator implements ConstraintValidator<Code, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return true;
        }
    }

    @Introspected
    public static class StrictCodeValidator implements ConstraintValidator<Code, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return false;
        }
    }

    @Introspected
    public static class Direct {
        @Code
        public String code = "x";

        public String getCode() {
            return code;
        }
    }

    @Introspected
    public static class Composed {
        @ProductCode
        public String code = "x";

        public String getCode() {
            return code;
        }
    }

    private static ValidatorFactory factory() {
        return new MicronautValidatorConfiguration()
            .addMapping(new ByteArrayInputStream(MAPPING.getBytes(StandardCharsets.UTF_8)))
            .buildValidatorFactory();
    }

    @Test
    void theRedefinedValidatorAppliesToTheConstraintDirectly() {
        try (ValidatorFactory factory = factory()) {
            assertEquals(1, factory.getValidator().validate(new Direct()).size());
        }
    }

    @Test
    void theRedefinedValidatorAppliesToTheConstraintComposed() {
        try (ValidatorFactory factory = factory()) {
            assertEquals(1, factory.getValidator().validate(new Composed()).size());
        }
    }

    @Test
    void theComposingDescriptorNamesTheRedefinedValidator() {
        try (ValidatorFactory factory = factory()) {
            ConstraintDescriptor<?> composed = factory.getValidator().getConstraintsForClass(Composed.class)
                .getConstraintsForProperty("code").getConstraintDescriptors().iterator().next();
            ConstraintDescriptor<?> composing = composed.getComposingConstraints().iterator().next();
            assertEquals(List.of(StrictCodeValidator.class), composing.getConstraintValidatorClasses());
        }
    }
}
