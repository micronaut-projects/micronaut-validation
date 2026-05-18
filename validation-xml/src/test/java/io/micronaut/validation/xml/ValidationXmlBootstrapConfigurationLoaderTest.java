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

import io.micronaut.validation.bootstrap.MicronautValidatorConfiguration;
import jakarta.validation.BootstrapConfiguration;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.executable.ExecutableType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationXmlBootstrapConfigurationLoaderTest {

    @Test
    void parsesValidationXmlBootstrapConfiguration() {
        String xml = """
            <validation-config xmlns="https://jakarta.ee/xml/ns/validation/configuration" version="3.1">
                <default-provider>example.Provider</default-provider>
                <message-interpolator>example.Interpolator</message-interpolator>
                <constraint-validator-factory>example.Factory</constraint-validator-factory>
                <traversable-resolver>example.TraversableResolver</traversable-resolver>
                <parameter-name-provider>example.ParameterNameProvider</parameter-name-provider>
                <clock-provider>example.ClockProvider</clock-provider>
                <value-extractor>example.ValueExtractor</value-extractor>
                <constraint-mapping>META-INF/constraints.xml</constraint-mapping>
                <executable-validation enabled="false">
                    <default-validated-executable-types>
                        <executable-type>GETTER_METHODS</executable-type>
                    </default-validated-executable-types>
                </executable-validation>
                <property name="micronaut.validator.spec.reflection.enabled">true</property>
            </validation-config>
            """;

        BootstrapConfiguration configuration = new ValidationXmlBootstrapConfigurationLoader()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertEquals("example.Provider", configuration.getDefaultProviderClassName());
        assertEquals("example.Interpolator", configuration.getMessageInterpolatorClassName());
        assertEquals("example.Factory", configuration.getConstraintValidatorFactoryClassName());
        assertEquals("example.TraversableResolver", configuration.getTraversableResolverClassName());
        assertEquals("example.ParameterNameProvider", configuration.getParameterNameProviderClassName());
        assertEquals("example.ClockProvider", configuration.getClockProviderClassName());
        assertEquals(Set.of("example.ValueExtractor"), configuration.getValueExtractorClassNames());
        assertEquals(Set.of("META-INF/constraints.xml"), configuration.getConstraintMappingResourcePaths());
        assertFalse(configuration.isExecutableValidationEnabled());
        assertEquals(Set.of(ExecutableType.GETTER_METHODS), configuration.getDefaultValidatedExecutableTypes());
        assertTrue(Boolean.parseBoolean(configuration.getProperties().get("micronaut.validator.spec.reflection.enabled")));
    }

    @Test
    void serviceLoaderAppliesValidationXmlToBootstrapConfiguration() {
        MicronautValidatorConfiguration configuration = new MicronautValidatorConfiguration();

        assertEquals("io.micronaut.validation.xml.TestClockProvider", configuration.getBootstrapConfiguration().getClockProviderClassName());
        assertTrue(configuration.getClockProvider() instanceof TestClockProvider);
        assertTrue(Boolean.parseBoolean(configuration.getProperties().get("micronaut.validator.spec.reflection.enabled")));
    }

    @Test
    void validationXmlProvidersAreAppliedToBuiltValidatorFactory() {
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(validatorFactory.getClockProvider() instanceof TestClockProvider);
        }
    }

    @Test
    void absoluteConstraintMappingResourcePathIsResolved(@TempDir Path temporaryDirectory) throws IOException {
        Path validationXml = temporaryDirectory.resolve("validation.xml");
        Path constraintMapping = temporaryDirectory.resolve("constraints.xml");
        Files.writeString(validationXml, """
            <validation-config xmlns="https://jakarta.ee/xml/ns/validation/configuration" version="3.1">
                <constraint-mapping>/constraints.xml</constraint-mapping>
            </validation-config>
            """);
        Files.writeString(constraintMapping, """
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <default-package>example.missing</default-package>
                <bean class="MissingBean" ignore-annotations="false">
                </bean>
            </constraint-mappings>
            """);
        ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader classLoader = new ClassLoader(previousClassLoader) {
            @Override
            public java.util.Enumeration<URL> getResources(String name) throws IOException {
                if ("META-INF/validation.xml".equals(name)) {
                    return Collections.enumeration(List.of(validationXml.toUri().toURL()));
                }
                return super.getResources(name);
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                if ("constraints.xml".equals(name)) {
                    try {
                        return Files.newInputStream(constraintMapping);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return super.getResourceAsStream(name);
            }
        };

        Thread.currentThread().setContextClassLoader(classLoader);
        try {
            MicronautValidatorConfiguration configuration = new MicronautValidatorConfiguration();

            assertThrows(ValidationException.class, configuration::buildValidatorFactory);
        } finally {
            Thread.currentThread().setContextClassLoader(previousClassLoader);
        }
    }

    @Test
    void executableTypeAllExpandsToConcreteExecutableTypes() {
        BootstrapConfiguration configuration = new ValidationXmlBootstrapConfigurationLoader()
            .parse(new ByteArrayInputStream("""
                <validation-config xmlns="https://jakarta.ee/xml/ns/validation/configuration" version="3.1">
                    <executable-validation>
                        <default-validated-executable-types>
                            <executable-type>ALL</executable-type>
                            <executable-type>CONSTRUCTORS</executable-type>
                        </default-validated-executable-types>
                    </executable-validation>
                </validation-config>
                """.getBytes(StandardCharsets.UTF_8)));

        assertEquals(
            Set.of(ExecutableType.CONSTRUCTORS, ExecutableType.GETTER_METHODS, ExecutableType.NON_GETTER_METHODS),
            configuration.getDefaultValidatedExecutableTypes()
        );
    }

    @Test
    void executableTypeNoneIsIgnoredWhenConcreteTypesAreConfigured() {
        BootstrapConfiguration configuration = new ValidationXmlBootstrapConfigurationLoader()
            .parse(new ByteArrayInputStream("""
                <validation-config xmlns="https://jakarta.ee/xml/ns/validation/configuration" version="3.1">
                    <executable-validation>
                        <default-validated-executable-types>
                            <executable-type>NONE</executable-type>
                            <executable-type>GETTER_METHODS</executable-type>
                        </default-validated-executable-types>
                    </executable-validation>
                </validation-config>
                """.getBytes(StandardCharsets.UTF_8)));

        assertEquals(Set.of(ExecutableType.GETTER_METHODS), configuration.getDefaultValidatedExecutableTypes());
    }

    @Test
    void emptyExecutableTypesAreRejected() {
        assertThrows(jakarta.validation.ValidationException.class, () -> new ValidationXmlBootstrapConfigurationLoader()
            .parse(new ByteArrayInputStream("""
                <validation-config xmlns="https://jakarta.ee/xml/ns/validation/configuration" version="3.1">
                    <executable-validation>
                        <default-validated-executable-types>
                        </default-validated-executable-types>
                    </executable-validation>
                </validation-config>
                """.getBytes(StandardCharsets.UTF_8))));
    }
}
