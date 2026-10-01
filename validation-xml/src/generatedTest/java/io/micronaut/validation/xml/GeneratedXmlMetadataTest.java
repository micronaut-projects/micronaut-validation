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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.validation.validator.DefaultValidatorConfiguration;
import io.micronaut.validation.validator.DefaultValidatorFactory;

import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotNull;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

class GeneratedXmlMetadataTest {
    @Test
    void defaultIgnorePolicyRemovesUnmappedExecutableAndNestedAnnotations() {
        String xml = "<constraint-mappings version=\"3.1\"><bean class=\"" + ExecutableBean.class.getName()
            + "\"/></constraint-mappings>";
        var provider = new XmlValidationMetadataProvider(getClass().getClassLoader(),
            Set.of(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        var method = provider.getBeanIntrospection(ExecutableBean.class).orElseThrow().getBeanMethods().stream()
            .filter(candidate -> candidate.getName().equals("handle")).findFirst().orElseThrow();
        assertTrue(method.getAnnotationMetadata().isEmpty());
        assertTrue(method.getArguments()[0].getAnnotationMetadata().isEmpty());
        assertTrue(method.getArguments()[0].getTypeParameters()[0].getAnnotationMetadata().isEmpty());
        assertTrue(method.getReturnType().asArgument().getTypeParameters()[0].getAnnotationMetadata().isEmpty());
    }

    @Test
    void xmlUsesGeneratedMembersAndAnnotationsWithoutEitherReflectionModule() {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("io.micronaut.reflection.ReflectionArguments"));
        assertThrows(
                ClassNotFoundException.class,
                () ->
                        Class.forName(
                                "io.micronaut.validation.reflection.ReflectionValidationSupport"));
        var provider = mapping(Bean.class, "value");
        DefaultValidatorConfiguration configuration = new DefaultValidatorConfiguration();
        configuration.setMetadataProviders(List.of(provider));
        try (var factory = new DefaultValidatorFactory(configuration)) {
            var violations = factory.getValidator().validate(new Bean());
            assertEquals(1, violations.size());
            assertThrows(ValidationException.class,
                () -> violations.iterator().next().getConstraintDescriptor().getAnnotation());
        }
    }

    @Test
    void annotatedPrivateFieldsRetainTheNarrowPermissionAndOtherFieldsFailClearly() {
        var provider = mapping(PrivateBean.class, "value");
        assertNull(
                provider.getBeanIntrospection(PrivateBean.class)
                        .orElseThrow()
                        .getRequiredProperty("value", String.class)
                        .get(new PrivateBean()));
        var forbidden = mapping(ForbiddenBean.class, "value");
        var failure =
                assertThrows(
                        ValidationException.class,
                        () ->
                                forbidden
                                        .getBeanIntrospection(ForbiddenBean.class)
                                        .orElseThrow()
                                        .getRequiredProperty("value", String.class)
                                        .get(new ForbiddenBean()));
        assertTrue(failure.getMessage().contains("@ReflectiveAccess"));
    }

    @Test
    void duplicateAndConflictingFieldMappingsAreRejected() {
        for (String second :
                List.of(
                        "jakarta.validation.constraints.NotNull",
                        "jakarta.validation.constraints.Size")) {
            String xml =
                    "<constraint-mappings version=\"3.1\"><bean class=\""
                            + Bean.class.getName()
                            + "\"><field name=\"value\"><constraint"
                            + " annotation=\"jakarta.validation.constraints.NotNull\"/></field><field"
                            + " name=\"value\"><constraint annotation=\""
                            + second
                            + "\"/></field></bean></constraint-mappings>";
            var failure =
                    assertThrows(
                            ValidationException.class,
                            () ->
                                    new XmlValidationMetadataProvider(
                                            getClass().getClassLoader(),
                                            Set.of(
                                                    new ByteArrayInputStream(
                                                            xml.getBytes(
                                                                    StandardCharsets.UTF_8)))));
            assertTrue(failure.getMessage().contains("Field configured more than once"));
        }
    }

    private static XmlValidationMetadataProvider mapping(Class<?> bean, String field) {
        String xml =
                "<constraint-mappings xmlns=\"https://jakarta.ee/xml/ns/validation/mapping\""
                    + " version=\"3.1\"><bean class=\""
                        + bean.getName()
                        + "\"><field name=\""
                        + field
                        + "\"><constraint"
                        + " annotation=\"jakarta.validation.constraints.NotNull\"/></field></bean></constraint-mappings>";
        return new XmlValidationMetadataProvider(
                bean.getClassLoader(),
                Set.of(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
    }

    @Introspected(
            accessKind = Introspected.AccessKind.FIELD,
            visibility = Introspected.Visibility.ANY)
    static class Bean {
        @NotNull String value;
    }

    @Introspected(
            accessKind = Introspected.AccessKind.FIELD,
            visibility = Introspected.Visibility.ANY)
    static class PrivateBean {
        @ReflectiveAccess @NotNull private String value;
    }

    @Introspected(
            accessKind = Introspected.AccessKind.FIELD,
            visibility = Introspected.Visibility.ANY)
    static class ForbiddenBean {
        @NotNull private String value;
    }

    @Introspected
    static class ExecutableBean {
        @NotNull
        public List<@NotNull String> handle(@NotNull List<@NotNull String> values) {
            return values;
        }
    }
}
