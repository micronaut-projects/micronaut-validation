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

import io.micronaut.core.annotation.AnnotationValue;
import jakarta.validation.ValidationException;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlValidationMetadataProviderTest {

    @Test
    void xmlConfiguredBeansIgnoreAnnotationsByDefault() {
        XmlValidationMetadataProvider provider = metadataProvider("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="java.lang.String">
                </bean>
            </constraint-mappings>
            """);

        assertTrue(provider.isBeanAnnotationMetadataIgnored(String.class));
        assertTrue(provider.isPropertyAnnotationMetadataIgnored(String.class, "value"));
    }

    @Test
    void rejectsFieldWithoutNameAttribute() {
        assertThrows(ValidationException.class, () -> metadataProvider("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="java.lang.String" ignore-annotations="false">
                    <field>
                        <constraint annotation="jakarta.validation.constraints.Pattern">
                            <element name="regexp">^[A-Z].*</element>
                        </constraint>
                    </field>
                </bean>
            </constraint-mappings>
            """));
    }

    @Test
    void rejectsReservedConstraintElementNames() {
        assertThrows(ValidationException.class, () -> metadataProvider("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="java.lang.String" ignore-annotations="false">
                    <field name="value">
                        <constraint annotation="jakarta.validation.constraints.Pattern">
                            <element name="message">invalid</element>
                            <element name="regexp">^[A-Z].*</element>
                        </constraint>
                    </field>
                </bean>
            </constraint-mappings>
            """));
    }

    @Test
    void rejectsUnknownFieldName() {
        assertThrows(ValidationException.class, () -> metadataProvider("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s" ignore-annotations="false">
                    <field name="missing"/>
                </bean>
            </constraint-mappings>
            """.formatted(BeanWithProperties.class.getName())));
    }

    @Test
    void rejectsUnknownGetterName() {
        assertThrows(ValidationException.class, () -> metadataProvider("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s" ignore-annotations="false">
                    <getter name="missing"/>
                </bean>
            </constraint-mappings>
            """.formatted(BeanWithProperties.class.getName())));
    }

    @Test
    void rejectsUnknownMappingVersion() {
        assertThrows(ValidationException.class, () -> metadataProvider("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="1.2">
            </constraint-mappings>
            """));
    }

    @Test
    void parsesPropertyGroupConversions() {
        XmlValidationMetadataProvider provider = metadataProvider("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s" ignore-annotations="false">
                    <field name="firstname">
                        <valid/>
                        <convert-group to="%s"/>
                    </field>
                </bean>
            </constraint-mappings>
            """.formatted(BeanWithProperties.class.getName(), Premium.class.getName()));

        List<AnnotationValue<ConvertGroup>> groupConversions = provider
            .getPropertyAnnotationMetadata(BeanWithProperties.class, "firstname")
            .getAnnotationValuesByType(ConvertGroup.class);

        assertEquals(1, groupConversions.size());
        assertEquals(Default.class, groupConversions.get(0).classValue("from").orElseThrow());
        assertEquals(Premium.class, groupConversions.get(0).classValue("to").orElseThrow());
    }

    @Test
    void rejectsMissingMandatoryConstraintAnnotationMember() {
        assertThrows(ValidationException.class, () -> metadataProvider("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="java.lang.String" ignore-annotations="false">
                    <field name="value">
                        <constraint annotation="jakarta.validation.constraints.Min"/>
                    </field>
                </bean>
            </constraint-mappings>
            """));
    }

    @Test
    void rejectsDuplicateBeanMapping() {
        String xml = """
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s" ignore-annotations="false"/>
            </constraint-mappings>
            """.formatted(BeanWithProperties.class.getName());

        assertThrows(ValidationException.class, () -> metadataProvider(xml, xml));
    }

    @Test
    void rejectsDuplicateFieldMapping() {
        assertThrows(ValidationException.class, () -> metadataProvider("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s" ignore-annotations="false">
                    <field name="firstname"/>
                    <field name="firstname"/>
                </bean>
            </constraint-mappings>
            """.formatted(BeanWithProperties.class.getName())));
    }

    @Test
    void rejectsDuplicateGetterMapping() {
        assertThrows(ValidationException.class, () -> metadataProvider("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s" ignore-annotations="false">
                    <getter name="firstname"/>
                    <getter name="firstname"/>
                </bean>
            </constraint-mappings>
            """.formatted(BeanWithProperties.class.getName())));
    }

    private static XmlValidationMetadataProvider metadataProvider(String... xmls) {
        Set<InputStream> mappingStreams = new LinkedHashSet<>();
        for (String xml : xmls) {
            mappingStreams.add(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        }
        return new XmlValidationMetadataProvider(
            Thread.currentThread().getContextClassLoader(),
            mappingStreams
        );
    }

    @SuppressWarnings("unused")
    private static final class BeanWithProperties {
        private String firstname;

        String getFirstname() {
            return firstname;
        }
    }

    private interface Premium {
    }
}
