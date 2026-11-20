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
import jakarta.validation.ValidationException;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void anUnmappedPropertyOfABeanIgnoringAnnotationsIsStillAProperty() {
        String mapping = """
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="io.micronaut.validation.xml.XmlConstraintValidationTest$Pair" ignore-annotations="true">
                    <getter name="first">
                        <constraint annotation="jakarta.validation.constraints.NotNull"/>
                    </getter>
                </bean>
            </constraint-mappings>
            """;
        try (ValidatorFactory factory = factory(mapping)) {
            var validator = factory.getValidator();
            assertEquals(0, validator.validateProperty(new Pair(), "second").size());
            assertEquals(0, validator.validateValue(Pair.class, "second", null).size());
            assertEquals(1, validator.validate(new Pair()).size());
        }
    }

    @Test
    void aConstraintMappedTwiceOnOneElementKeepsBothOccurrences() {
        String mapping = """
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="io.micronaut.validation.xml.XmlConstraintValidationTest$Text" ignore-annotations="true">
                    <field name="value">
                        <constraint annotation="jakarta.validation.constraints.Pattern">
                            <element name="regexp">a+</element>
                        </constraint>
                        <constraint annotation="jakarta.validation.constraints.Pattern">
                            <element name="regexp">b+</element>
                        </constraint>
                    </field>
                    <getter name="sized">
                        <constraint annotation="jakarta.validation.constraints.Size">
                            <element name="min">1</element>
                        </constraint>
                        <constraint annotation="jakarta.validation.constraints.Size">
                            <groups><value>io.micronaut.validation.xml.XmlConstraintValidationTest$Strict</value></groups>
                            <element name="max">5</element>
                        </constraint>
                    </getter>
                </bean>
            </constraint-mappings>
            """;
        try (ValidatorFactory factory = factory(mapping)) {
            var validator = factory.getValidator();
            assertEquals(2, validator.validateProperty(new Text("x", "abc"), "value").size());
            assertEquals(1, validator.validateProperty(new Text("a", ""), "sized").size());
            assertEquals(0, validator.validateProperty(new Text("a", "abcdefg"), "sized").size());
            assertEquals(1, validator.validateProperty(new Text("a", "abcdefg"), "sized", Strict.class).size());
        }
    }

    @Test
    void aParameterConstraintMappedOnANonBeanMethodIsInheritedByTheOverride() throws NoSuchMethodException {
        String mapping = """
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="io.micronaut.validation.xml.XmlConstraintValidationTest$Service">
                    <method name="process">
                        <parameter type="java.lang.String">
                            <constraint annotation="jakarta.validation.constraints.NotNull"/>
                        </parameter>
                    </method>
                </bean>
            </constraint-mappings>
            """;
        try (ValidatorFactory factory = factory(mapping)) {
            var violations = factory.getValidator().forExecutables().validateParameters(
                new SubService(), SubService.class.getMethod("process", String.class), new Object[] {null});
            assertEquals(1, violations.size());
        }
        try (ValidatorFactory factory = factory(mapping.replace("$Service", "$PlainService"))) {
            var violations = factory.getValidator().forExecutables().validateParameters(
                new PlainSubService(), PlainSubService.class.getMethod("process", String.class), new Object[] {null});
            assertEquals(1, violations.size());
        }
    }

    @Test
    void aNonNumericIntegerElementIsAValidationException() {
        String mapping = """
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="io.micronaut.validation.xml.XmlConstraintValidationTest$Listed">
                    <field name="items">
                        <container-element-type type-argument-index="first">
                            <constraint annotation="jakarta.validation.constraints.NotNull"/>
                        </container-element-type>
                    </field>
                </bean>
            </constraint-mappings>
            """;
        assertThrows(ValidationException.class, () -> factory(mapping).close());
    }

    @Test
    void aConstraintDefinedTwiceInOneMappingIsAValidationException() {
        String mapping = """
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <constraint-definition annotation="jakarta.validation.constraints.NotNull">
                    <validated-by include-existing-validators="true"/>
                </constraint-definition>
                <constraint-definition annotation="jakarta.validation.constraints.NotNull">
                    <validated-by include-existing-validators="true"/>
                </constraint-definition>
            </constraint-mappings>
            """;
        assertThrows(ValidationException.class, () -> factory(mapping).close());
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

    public interface Strict {
    }

    @Introspected
    public static class Text {
        String value;
        private final String sized;

        public Text(String value, String sized) {
            this.value = value;
            this.sized = sized;
        }

        public String getSized() {
            return sized;
        }
    }

    @Introspected
    public static class Listed {
        java.util.List<String> items = java.util.List.of();
    }

    public static class PlainService {
        public void process(String value) {
        }
    }

    public static class PlainSubService extends PlainService {
        @Override
        public void process(String value) {
        }
    }

    @Introspected
    public static class Service {
        public void process(String value) {
        }
    }

    @Introspected
    public static class SubService extends Service {
        @Override
        @io.micronaut.context.annotation.Executable
        public void process(String value) {
        }
    }

    @Introspected
    public static class Pair {
        public String getFirst() {
            return null;
        }

        @NotNull
        public String getSecond() {
            return null;
        }
    }
}
