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
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.type.Argument;
import io.micronaut.validation.validator.ConstraintValidatorOverrides;
import io.micronaut.validation.validator.IntrospectedBeanDescriptor;
import io.micronaut.validation.validator.ReflectionSupport;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.MethodDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlBeanIntrospectorTest {

    @Test
    void xmlConfiguredBeansIgnoreAnnotationsByDefault() {
        XmlBeanIntrospector provider = introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="io.micronaut.validation.xml.XmlPropertyIgnoreBean">
                </bean>
            </constraint-mappings>
            """);

        assertFalse(descriptor(provider, XmlPropertyIgnoreBean.class).isBeanConstrained());
    }

    @Test
    void rejectsFieldWithoutNameAttribute() {
        assertThrows(ValidationException.class, () -> introspector("""
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
        assertThrows(ValidationException.class, () -> introspector("""
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
        assertThrows(ValidationException.class, () -> introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s" ignore-annotations="false">
                    <field name="missing"/>
                </bean>
            </constraint-mappings>
            """.formatted(BeanWithProperties.class.getName())));
    }

    @Test
    void rejectsUnknownGetterName() {
        assertThrows(ValidationException.class, () -> introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s" ignore-annotations="false">
                    <getter name="missing"/>
                </bean>
            </constraint-mappings>
            """.formatted(BeanWithProperties.class.getName())));
    }

    @Test
    void rejectsUnknownMappingVersion() {
        assertThrows(ValidationException.class, () -> introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="1.2">
            </constraint-mappings>
            """));
    }

    @Test
    void rejectsUnknownRootElement() {
        assertThrows(ValidationException.class, () -> introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <invalid/>
            </constraint-mappings>
            """));
    }

    @Test
    void rejectsConstraintMappingDoctype() {
        assertThrows(ValidationException.class, () -> introspector("""
            <!DOCTYPE constraint-mappings [
                <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <default-package>&xxe;</default-package>
            </constraint-mappings>
            """));
    }

    @Test
    void rejectsConstraintMappingXInclude() {
        assertThrows(ValidationException.class, () -> introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping"
                xmlns:xi="http://www.w3.org/2001/XInclude"
                version="3.1">
                <xi:include href="file:///etc/passwd"/>
            </constraint-mappings>
            """));
    }

    @Test
    void parsesPropertyGroupConversions() {
        XmlBeanIntrospector provider = introspector("""
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
            .findIntrospection(BeanWithProperties.class).orElseThrow()
            .getProperty("firstname").orElseThrow()
            .getAnnotationMetadata()
            .getAnnotationValuesByType(ConvertGroup.class);

        assertEquals(1, groupConversions.size());
        assertEquals(Default.class, groupConversions.get(0).classValue("from").orElseThrow());
        assertEquals(Premium.class, groupConversions.get(0).classValue("to").orElseThrow());
    }

    @Test
    void rejectsMissingMandatoryConstraintAnnotationMember() {
        assertThrows(ValidationException.class, () -> introspector("""
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

        assertThrows(ValidationException.class, () -> introspector(xml, xml));
    }

    @Test
    void rejectsDuplicateFieldMapping() {
        assertThrows(ValidationException.class, () -> introspector("""
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
        assertThrows(ValidationException.class, () -> introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s" ignore-annotations="false">
                    <getter name="firstname"/>
                    <getter name="firstname"/>
                </bean>
            </constraint-mappings>
            """.formatted(BeanWithProperties.class.getName())));
    }

    @Test
    void resolvesDefaultPackageForJvmArrayParameterTypes() {
        XmlBeanIntrospector provider = introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <default-package>io.micronaut.validation.xml</default-package>
                <bean class="XmlArrayParameterBean">
                    <method name="add">
                        <parameter type="[LXmlArrayParameterBean;">
                            <constraint annotation="jakarta.validation.constraints.NotNull"/>
                        </parameter>
                    </method>
                </bean>
            </constraint-mappings>
            """);

        MethodDescriptor descriptor = descriptor(provider, XmlArrayParameterBean.class)
            .getConstraintsForMethod("add", XmlArrayParameterBean[].class);

        assertEquals(XmlArrayParameterBean[].class, descriptor.getParameterDescriptors().get(0).getElementClass());
    }

    @Test
    void executableIgnoreAnnotationsControlsReflectedExecutableMetadata() {
        XmlBeanIntrospector provider = introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <default-package>io.micronaut.validation.xml</default-package>
                <bean class="XmlExecutableIgnoreBean" ignore-annotations="false">
                    <method name="handle">
                        <parameter type="java.lang.String" ignore-annotations="true"/>
                        <parameter type="java.lang.String"/>
                        <cross-parameter ignore-annotations="true"/>
                        <return-value ignore-annotations="true"/>
                    </method>
                </bean>
            </constraint-mappings>
            """);

        MethodDescriptor descriptor = descriptor(provider, XmlExecutableIgnoreBean.class)
            .getConstraintsForMethod("handle", String.class, String.class);

        assertFalse(descriptor.getCrossParameterDescriptor().hasConstraints());
        assertFalse(descriptor.getReturnValueDescriptor().hasConstraints());
        assertFalse(descriptor.getParameterDescriptors().get(0).hasConstraints());
        assertTrue(descriptor.getParameterDescriptors().get(1).hasConstraints());
    }

    @Test
    void fieldDescriptorsHonorIgnoreAnnotations() {
        XmlBeanIntrospector provider = introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s" ignore-annotations="false">
                    <field name="ignored" ignore-annotations="true"/>
                    <field name="included">
                        <constraint annotation="jakarta.validation.constraints.Pattern">
                            <element name="regexp">[a-z]+</element>
                        </constraint>
                    </field>
                </bean>
            </constraint-mappings>
            """.formatted(XmlPropertyIgnoreBean.class.getName()));

        PropertyDescriptor ignored = descriptor(provider, XmlPropertyIgnoreBean.class)
            .getConstraintsForProperty("ignored");
        PropertyDescriptor included = descriptor(provider, XmlPropertyIgnoreBean.class)
            .getConstraintsForProperty("included");

        assertFalse(ignored != null && ignored.hasConstraints());
        assertEquals(2, included.getConstraintDescriptors().size());
    }

    @Test
    void getterDescriptorsHonorIgnoreAnnotations() {
        XmlBeanIntrospector provider = introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s" ignore-annotations="false">
                    <getter name="ignored" ignore-annotations="true"/>
                    <getter name="included">
                        <constraint annotation="jakarta.validation.constraints.Pattern">
                            <element name="regexp">[a-z]+</element>
                        </constraint>
                    </getter>
                </bean>
            </constraint-mappings>
            """.formatted(XmlPropertyIgnoreBean.class.getName()));

        PropertyDescriptor ignored = descriptor(provider, XmlPropertyIgnoreBean.class)
            .getConstraintsForProperty("ignored");
        PropertyDescriptor included = descriptor(provider, XmlPropertyIgnoreBean.class)
            .getConstraintsForProperty("included");

        assertFalse(ignored != null && ignored.hasConstraints());
        assertEquals(2, included.getConstraintDescriptors().size());
    }

    @Test
    void parsesPropertyContainerElementConstraints() {
        XmlBeanIntrospector provider = introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s">
                    <field name="lines">
                        <container-element-type type-argument-index="1">
                            <constraint annotation="jakarta.validation.constraints.DecimalMin">
                                <element name="value">
                                    <value>0</value>
                                </element>
                                <element name="inclusive">
                                    <value>false</value>
                                </element>
                            </constraint>
                        </container-element-type>
                    </field>
                </bean>
            </constraint-mappings>
            """.formatted(XmlContainerElementBean.class.getName()));

        PropertyDescriptor descriptor = descriptor(provider, XmlContainerElementBean.class)
            .getConstraintsForProperty("lines");

        assertEquals(1, descriptor.getConstrainedContainerElementTypes().size());
        var containerElement = descriptor.getConstrainedContainerElementTypes().iterator().next();
        assertEquals(Map.class, containerElement.getContainerClass());
        assertEquals(1, containerElement.getTypeArgumentIndex());
        assertEquals(BigDecimal.class, containerElement.getElementClass());
        assertEquals(DecimalMin.class, containerElement.getConstraintDescriptors().iterator().next().getAnnotation().annotationType());
    }

    @Test
    void defaultsContainerElementTypeArgumentIndexForSingleTypeArgumentContainers() {
        XmlBeanIntrospector provider = introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s">
                    <field name="nickname">
                        <container-element-type>
                            <constraint annotation="jakarta.validation.constraints.NotNull"/>
                        </container-element-type>
                    </field>
                </bean>
            </constraint-mappings>
            """.formatted(XmlContainerElementBean.class.getName()));

        PropertyDescriptor descriptor = descriptor(provider, XmlContainerElementBean.class)
            .getConstraintsForProperty("nickname");

        assertEquals(1, descriptor.getConstrainedContainerElementTypes().size());
        var containerElement = descriptor.getConstrainedContainerElementTypes().iterator().next();
        assertEquals(Optional.class, containerElement.getContainerClass());
        assertEquals(0, containerElement.getTypeArgumentIndex());
        assertEquals(String.class, containerElement.getElementClass());
        assertEquals(NotNull.class, containerElement.getConstraintDescriptors().iterator().next().getAnnotation().annotationType());
    }

    @Test
    void parsesExecutableContainerElementConstraints() {
        XmlBeanIntrospector provider = introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s">
                    <method name="useNickname">
                        <parameter type="java.util.Optional">
                            <container-element-type>
                                <constraint annotation="jakarta.validation.constraints.NotNull"/>
                            </container-element-type>
                        </parameter>
                        <return-value>
                            <container-element-type>
                                <constraint annotation="jakarta.validation.constraints.NotNull"/>
                            </container-element-type>
                        </return-value>
                    </method>
                </bean>
            </constraint-mappings>
            """.formatted(XmlContainerElementBean.class.getName()));

        MethodDescriptor descriptor = descriptor(provider, XmlContainerElementBean.class)
            .getConstraintsForMethod("useNickname", Optional.class);

        var parameterContainerElement = descriptor.getParameterDescriptors()
            .get(0)
            .getConstrainedContainerElementTypes()
            .iterator()
            .next();
        assertEquals(Optional.class, parameterContainerElement.getContainerClass());
        assertEquals(0, parameterContainerElement.getTypeArgumentIndex());
        assertEquals(String.class, parameterContainerElement.getElementClass());
        assertEquals(NotNull.class, parameterContainerElement.getConstraintDescriptors().iterator().next().getAnnotation().annotationType());

        var returnContainerElement = descriptor.getReturnValueDescriptor()
            .getConstrainedContainerElementTypes()
            .iterator()
            .next();
        assertEquals(Optional.class, returnContainerElement.getContainerClass());
        assertEquals(0, returnContainerElement.getTypeArgumentIndex());
        assertEquals(String.class, returnContainerElement.getElementClass());
        assertEquals(NotNull.class, returnContainerElement.getConstraintDescriptors().iterator().next().getAnnotation().annotationType());
    }

    @Target(METHOD)
    @Retention(RUNTIME)
    @Constraint(validatedBy = CrossParameterValidator.class)
    @interface CrossParameterConstraint {

        String message() default "invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @SupportedValidationTarget(ValidationTarget.PARAMETERS)
    static final class CrossParameterValidator implements ConstraintValidator<CrossParameterConstraint, Object[]> {

        @Override
        public boolean isValid(Object[] value, ConstraintValidatorContext context) {
            return false;
        }
    }

    @Test
    void describesAMappedBeanTheArchiveHasNoIntrospectionFor() {
        XmlBeanIntrospector provider = introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s">
                    <field name="lines">
                        <container-element-type type-argument-index="1">
                            <constraint annotation="jakarta.validation.constraints.NotNull"/>
                        </container-element-type>
                    </field>
                </bean>
            </constraint-mappings>
            """.formatted(XmlDescribedBean.class.getName()));

        BeanIntrospection<XmlDescribedBean> introspection = provider.findIntrospection(XmlDescribedBean.class).orElseThrow();

        assertEquals(XmlDescribedBean.class, introspection.getBeanType());
        assertEquals(List.of("lines"), List.of(introspection.getPropertyNames()));
        BeanProperty<XmlDescribedBean, Object> property = introspection.getProperty("lines").orElseThrow();
        assertEquals(Map.class, property.getType());
        Argument<?>[] typeParameters = property.asArgument().getTypeParameters();
        assertEquals(String.class, typeParameters[0].getType());
        assertEquals(BigDecimal.class, typeParameters[1].getType());
    }

    @Test
    void readsTheValueOfAMemberAMappingNamesWhateverItsVisibility() {
        XmlBeanIntrospector provider = introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s">
                    <field name="lines">
                        <container-element-type type-argument-index="0">
                            <constraint annotation="jakarta.validation.constraints.NotNull"/>
                        </container-element-type>
                    </field>
                    <getter name="label">
                        <constraint annotation="jakarta.validation.constraints.NotNull"/>
                    </getter>
                </bean>
            </constraint-mappings>
            """.formatted(XmlDescribedBean.class.getName()));

        BeanIntrospection<XmlDescribedBean> introspection = provider.findIntrospection(XmlDescribedBean.class).orElseThrow();
        XmlDescribedBean bean = new XmlDescribedBean();

        assertEquals(Map.of("a", BigDecimal.ONE), introspection.getProperty("lines").orElseThrow().get(bean));
        assertEquals("label", introspection.getProperty("label").orElseThrow().get(bean));
    }

    @Test
    void readsTheConstraintsAMappedMemberDeclaresOnItsTypeArguments() {
        XmlBeanIntrospector provider = introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s">
                    <field name="lines" ignore-annotations="false">
                        <container-element-type type-argument-index="1">
                            <constraint annotation="jakarta.validation.constraints.NotNull"/>
                        </container-element-type>
                    </field>
                </bean>
            </constraint-mappings>
            """.formatted(XmlDescribedBean.class.getName()));

        Argument<?> lines = provider.findIntrospection(XmlDescribedBean.class)
            .orElseThrow()
            .getProperty("lines")
            .orElseThrow()
            .asArgument();

        assertTrue(lines.getTypeParameters()[0].getAnnotationMetadata().hasAnnotation(NotBlank.class));
        assertTrue(lines.getTypeParameters()[0].getAnnotationMetadata().hasStereotype(Constraint.class));
        assertFalse(lines.getTypeParameters()[1].getAnnotationMetadata().hasAnnotation(NotBlank.class));
    }

    @Test
    void describesNothingForATypeNoMappingNames() {
        XmlBeanIntrospector provider = introspector("""
            <constraint-mappings xmlns="https://jakarta.ee/xml/ns/validation/mapping" version="3.1">
                <bean class="%s">
                    <field name="lines">
                        <container-element-type type-argument-index="1">
                            <constraint annotation="jakarta.validation.constraints.NotNull"/>
                        </container-element-type>
                    </field>
                </bean>
            </constraint-mappings>
            """.formatted(XmlDescribedBean.class.getName()));

        // a type no mapping names is described by the decorated introspector alone
        assertFalse(provider.findIntrospection(XmlContainerElementBean.class).orElse(null) instanceof XmlBeanIntrospection);
        assertTrue(provider.findIntrospection(XmlDescribedBean.class).orElse(null) instanceof XmlBeanIntrospection);
    }

    private static XmlBeanIntrospector introspector(String... xmls) {
        Set<InputStream> mappingStreams = new LinkedHashSet<>();
        for (String xml : xmls) {
            mappingStreams.add(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        }
        return XmlBeanIntrospector.of(support(), mappingStreams);
    }

    /** The metadata access of a bootstrapped validator: the generated introspections, supplemented when the reflection module is there. */
    private static ReflectionSupport support() {
        ReflectionSupport support = ReflectionSupport.forClassLoader(Thread.currentThread().getContextClassLoader());
        return support.withIntrospector(support.supplemented(support.introspector()));
    }

    private static BeanDescriptor descriptor(XmlBeanIntrospector introspector, Class<?> beanType) {
        return new IntrospectedBeanDescriptor(support().withIntrospector(introspector),
            introspector.findIntrospection(beanType).orElseThrow(), ConstraintValidatorOverrides.NONE);
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

final class XmlArrayParameterBean {

    void add(XmlArrayParameterBean... beans) {
        // Method body is irrelevant; the test validates XML parameter metadata.
    }
}

final class XmlExecutableIgnoreBean {

    @Valid
    @XmlBeanIntrospectorTest.CrossParameterConstraint
    @NotNull
    String handle(@Valid @NotNull String ignored, @Valid @NotNull String applied) {
        return "";
    }
}

final class XmlPropertyIgnoreBean {

    @NotNull
    private String ignored = "";

    @NotNull
    private String included = "";

    @NotNull
    String getIgnored() {
        return ignored;
    }

    @NotNull
    String getIncluded() {
        return included;
    }
}

final class XmlDescribedBean {

    @SuppressWarnings("unused")
    private final Map<@NotBlank String, BigDecimal> lines = Map.of("a", BigDecimal.ONE);

    @SuppressWarnings("unused")
    private String getLabel() {
        return "label";
    }
}

final class XmlContainerElementBean {

    @SuppressWarnings("unused")
    private Map<String, BigDecimal> lines;

    @SuppressWarnings("unused")
    private Optional<String> nickname;

    @SuppressWarnings("unused")
    Optional<String> useNickname(Optional<String> nickname) {
        return nickname;
    }
}
