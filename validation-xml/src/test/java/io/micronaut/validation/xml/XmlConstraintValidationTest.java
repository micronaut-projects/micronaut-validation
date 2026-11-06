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
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XmlConstraintValidationTest {

    @Test
    void includingExistingValidatorsKeepsTheBuiltInOnes() {
        String mapping = """
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <constraint-definition annotation="jakarta.validation.constraints.NotNull">
                    <validated-by include-existing-validators="true"/>
                </constraint-definition>
            </constraint-mappings>
            """;
        try (ValidatorFactory factory = factory(mapping)) {
            assertEquals(1, factory.getValidator().validate(new Named()).size());
        }
    }

    @Test
    void mappedFieldAndGetterEachReadTheirOwnValue() {
        String mapping = """
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="io.micronaut.validation.xml.XmlConstraintValidationTest$Computed" ignore-annotations="true">
                    <field name="name">
                        <constraint annotation="jakarta.validation.constraints.Size">
                            <element name="min">1</element>
                        </constraint>
                    </field>
                    <getter name="name">
                        <constraint annotation="jakarta.validation.constraints.NotNull"/>
                    </getter>
                </bean>
            </constraint-mappings>
            """;
        try (ValidatorFactory factory = factory(mapping)) {
            var violations = factory.getValidator().validate(new Computed());
            assertEquals(1, violations.size());
            assertEquals(NotNull.class, violations.iterator().next().getConstraintDescriptor().getAnnotation().annotationType());
        }
    }

    private static ValidatorFactory factory(String mapping) {
        return new MicronautValidatorConfiguration()
            .addMapping(new ByteArrayInputStream(mapping.getBytes(StandardCharsets.UTF_8)))
            .buildValidatorFactory();
    }

    @Introspected
    public static class Computed {
        @NotNull
        String name = "field";

        public String getName() {
            return null;
        }
    }

    @Introspected
    public static class Named {
        @NotNull
        String name;

        public String getName() {
            return name;
        }
    }
}
