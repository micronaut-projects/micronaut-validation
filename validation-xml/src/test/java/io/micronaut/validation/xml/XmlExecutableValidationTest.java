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

import io.micronaut.context.ApplicationContext;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Method interception in an application context follows the {@code executable-validation} element of
 * {@code META-INF/validation.xml}.
 */
class XmlExecutableValidationTest {

    @TempDir
    Path directory;

    @Test
    void executableValidationDisabledInValidationXmlValidatesNoInterceptedMethod() throws IOException {
        try (ApplicationContext context = run("""
            <validation-config xmlns="https://jakarta.ee/xml/ns/validation/configuration" version="3.1">
                <executable-validation enabled="false"/>
            </validation-config>
            """)) {
            Service service = context.getBean(Service.class);
            assertDoesNotThrow(service::getName);
            assertDoesNotThrow(() -> service.greet(""));
        }
    }

    @Test
    void theExecutableTypesOfValidationXmlDecideWhichMethodsAreValidated() throws IOException {
        try (ApplicationContext context = run("""
            <validation-config xmlns="https://jakarta.ee/xml/ns/validation/configuration" version="3.1">
                <executable-validation>
                    <default-validated-executable-types>
                        <executable-type>GETTER_METHODS</executable-type>
                    </default-validated-executable-types>
                </executable-validation>
            </validation-config>
            """)) {
            Service service = context.getBean(Service.class);
            assertThrows(ConstraintViolationException.class, service::getName);
            assertDoesNotThrow(() -> service.greet(""));
        }
    }

    private ApplicationContext run(String validationXml) throws IOException {
        Path file = Files.writeString(directory.resolve("validation.xml"), validationXml);
        URL url = file.toUri().toURL();
        ClassLoader parent = getClass().getClassLoader();
        ClassLoader classLoader = new ClassLoader(parent) {
            @Override
            public java.util.Enumeration<URL> getResources(String name) throws IOException {
                if ("META-INF/validation.xml".equals(name)) {
                    return Collections.enumeration(List.of(url));
                }
                return super.getResources(name);
            }
        };
        return ApplicationContext.builder().classLoader(classLoader).start();
    }

    @Singleton
    public static class Service {
        @NotNull
        public String getName() {
            return null;
        }

        public String greet(@NotBlank String name) {
            return "Hello " + name;
        }
    }
}
