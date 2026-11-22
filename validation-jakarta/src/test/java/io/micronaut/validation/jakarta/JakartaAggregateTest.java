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
package io.micronaut.validation.jakarta;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.metadata.ConstraintDescriptor;
import javafx.beans.property.ListProperty;
import javafx.beans.property.MapProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.SetProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.beans.property.SimpleMapProperty;
import javafx.beans.property.SimpleSetProperty;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.annotation.Retention;
import java.util.List;
import java.util.Set;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JakartaAggregateTest {

    @Test
    void aggregateProvidesBootstrapReflectionAndElInterpolation() {
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            Set<ConstraintViolation<PlainBean>> violations = validatorFactory.getValidator()
                .validate(new PlainBean("ab"));

            assertEquals(1, violations.size());
            ConstraintViolation<PlainBean> violation = violations.iterator().next();
            assertEquals("name", violation.getPropertyPath().toString());
            assertEquals("length 2 must be at least 3", violation.getMessage());
        }
    }

    @Test
    void aggregateProvidesJavaFxValueExtractors() {
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            Set<ConstraintViolation<JavaFxBean>> violations = validatorFactory.getValidator()
                .validate(new JavaFxBean());

            assertEquals(6, violations.size());
            assertTrue(violations.stream().anyMatch(violation ->
                violation.getPropertyPath().toString().equals("rating") && Double.valueOf(4.5).equals(violation.getInvalidValue())));
            assertTrue(violations.stream().anyMatch(violation -> violation.getPropertyPath().toString().equals("tags")));
            assertTrue(violations.stream().anyMatch(violation -> violation.getPropertyPath().toString().contains("names")));
            assertTrue(violations.stream().anyMatch(violation -> violation.getPropertyPath().toString().contains("aliases")));
            assertTrue(violations.stream().anyMatch(violation -> violation.getPropertyPath().toString().contains("scores")));
        }
    }

    @Test
    void aggregateAppliesProgrammaticXmlConstraintDefinitionsToReflectionMetadata() {
        String mapping = """
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.0">
                <constraint-definition annotation="io.micronaut.validation.jakarta.JakartaAggregateTest$XmlLength">
                    <validated-by include-existing-validators="true">
                        <value>io.micronaut.validation.jakarta.JakartaAggregateTest$XmlLengthXmlValidator</value>
                    </validated-by>
                </constraint-definition>
            </constraint-mappings>
            """;

        try (ValidatorFactory validatorFactory = Validation.byDefaultProvider()
            .configure()
            .addMapping(new ByteArrayInputStream(mapping.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .buildValidatorFactory()) {
            ConstraintDescriptor<?> descriptor = validatorFactory.getValidator()
                .getConstraintsForClass(XmlLengthBean.class)
                .getConstraintsForProperty("name")
                .getConstraintDescriptors()
                .iterator()
                .next();

            List<Class<? extends ConstraintValidator<?, ?>>> validators = (List) descriptor.getConstraintValidatorClasses();
            assertEquals(2, validators.size());
            assertTrue(validators.contains(XmlLengthValidator.class));
            assertTrue(validators.contains(XmlLengthXmlValidator.class));
        }
    }

    static final class PlainBean {
        @Size(min = 3, message = "length ${validatedValue.length()} must be at least {min}")
        private final String name;

        PlainBean(String name) {
            this.name = name;
        }
    }

    static final class JavaFxBean {
        @Max(3)
        private final ReadOnlyDoubleWrapper rating = new ReadOnlyDoubleWrapper(4.5);

        @Size(min = 2)
        private final ListProperty<String> tags = new SimpleListProperty<>(FXCollections.observableArrayList("one"));

        private final ListProperty<@NotBlank String> names = new SimpleListProperty<>(FXCollections.observableArrayList(""));

        private final SetProperty<@NotBlank String> aliases = new SimpleSetProperty<>(FXCollections.observableSet(""));

        private final MapProperty<@NotBlank String, @NotBlank String> scores = new SimpleMapProperty<>(FXCollections.observableHashMap());

        JavaFxBean() {
            scores.put("", "");
        }
    }

    static final class XmlLengthBean {
        @XmlLength
        private String name;
    }

    @jakarta.validation.Constraint(validatedBy = XmlLengthValidator.class)
    @java.lang.annotation.Target(FIELD)
    @Retention(RUNTIME)
    @interface XmlLength {
        String message() default "invalid";

        Class<?>[] groups() default {};

        Class<? extends jakarta.validation.Payload>[] payload() default {};
    }

    static final class XmlLengthValidator implements ConstraintValidator<XmlLength, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return true;
        }
    }

    static final class XmlLengthXmlValidator implements ConstraintValidator<XmlLength, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return true;
        }
    }
}
