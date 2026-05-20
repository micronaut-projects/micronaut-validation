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
package io.micronaut.validation.reflection;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.validation.validator.DefaultValidatorConfiguration;
import io.micronaut.validation.validator.Validator;
import io.micronaut.validation.validator.constraints.DefaultInternalConstraintValidatorFactory;
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.GroupSequence;
import jakarta.validation.OverridesAttribute;
import jakarta.validation.Path;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstructorDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ElementDescriptor;
import jakarta.validation.metadata.MethodDescriptor;
import jakarta.validation.metadata.MethodType;
import jakarta.validation.metadata.PropertyDescriptor;
import jakarta.validation.metadata.Scope;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionValidatorTest {

    @Test
    void validatesBeanWithoutMicronautIntrospectionWhenReflectionFallbackIsPresent() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<PlainBean>> violations = validator.validate(new PlainBean(""));

            assertEquals(1, violations.size());
            ConstraintViolation<PlainBean> violation = violations.iterator().next();
            assertEquals("name", violation.getPropertyPath().toString());
            assertEquals("", violation.getInvalidValue());
        }
    }

    @Test
    void canDisableReflectionFallbackAtRuntime() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            ValidationException exception = assertThrows(ValidationException.class, () -> validator.validate(new PlainBean("")));

            assertTrue(exception.getMessage().contains("Bean introspection not found"));
        }
    }

    @Test
    void validatesConstructorParametersWithoutMicronautExecutableMetadata() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            Constructor<PlainBean> constructor = PlainBean.class.getDeclaredConstructor(String.class);
            Object[] parameterValues = new Object[]{""};

            Set<ConstraintViolation<PlainBean>> violations = validator.forExecutables()
                .validateConstructorParameters(constructor, parameterValues);

            assertEquals(1, violations.size());
            ConstraintViolation<PlainBean> violation = violations.iterator().next();
            assertEquals("", violation.getInvalidValue());
            assertSame(parameterValues, violation.getExecutableParameters());
            assertNull(violation.getExecutableReturnValue());
            Iterator<Path.Node> nodes = violation.getPropertyPath().iterator();
            Path.Node constructorNode = nodes.next();
            assertEquals(ElementKind.CONSTRUCTOR, constructorNode.getKind());
            assertEquals("PlainBean", constructorNode.getName());
            Path.ParameterNode parameterNode = nodes.next().as(Path.ParameterNode.class);
            assertEquals(ElementKind.PARAMETER, parameterNode.getKind());
            assertEquals("name", parameterNode.getName());
            assertEquals(0, parameterNode.getParameterIndex());
            assertFalse(nodes.hasNext());
        }
    }

    @Test
    void validatesInheritedMethodParametersWithoutMicronautExecutableMetadata() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            Method method = ChildService.class.getDeclaredMethod("submit", String.class);
            Object[] parameterValues = new Object[]{null};

            Set<ConstraintViolation<ChildService>> violations = validator.forExecutables()
                .validateParameters(new ChildService(), method, parameterValues);

            assertEquals(2, violations.size());
            assertTrue(violations.stream().allMatch(violation -> violation.getExecutableParameters() == parameterValues));
            assertTrue(violations.stream().allMatch(violation -> violation.getExecutableReturnValue() == null));
            assertTrue(violations.stream().anyMatch(violation -> violation.getPropertyPath().toString().equals("submit.name")));
            ConstraintViolation<ChildService> crossParameterViolation = violations.stream()
                .filter(violation -> violation.getPropertyPath().toString().equals("submit.<cross-parameter>"))
                .findFirst()
                .orElseThrow();
            Iterator<Path.Node> nodes = crossParameterViolation.getPropertyPath().iterator();
            assertEquals(ElementKind.METHOD, nodes.next().getKind());
            Path.Node crossParameterNode = nodes.next();
            assertEquals(ElementKind.CROSS_PARAMETER, crossParameterNode.getKind());
            assertEquals("<cross-parameter>", crossParameterNode.getName());
            assertFalse(nodes.hasNext());
        }
    }

    @Test
    void validatesConstructorCrossParameterPathWithoutMicronautExecutableMetadata() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            Constructor<CrossParameterConstructorBean> constructor = CrossParameterConstructorBean.class.getDeclaredConstructor(String.class);

            Set<ConstraintViolation<CrossParameterConstructorBean>> violations = validator.forExecutables()
                .validateConstructorParameters(constructor, new Object[]{"valid"});

            assertEquals(1, violations.size());
            ConstraintViolation<CrossParameterConstructorBean> violation = violations.iterator().next();
            assertEquals("CrossParameterConstructorBean.<cross-parameter>", violation.getPropertyPath().toString());
            Iterator<Path.Node> nodes = violation.getPropertyPath().iterator();
            assertEquals(ElementKind.CONSTRUCTOR, nodes.next().getKind());
            Path.Node crossParameterNode = nodes.next();
            assertEquals(ElementKind.CROSS_PARAMETER, crossParameterNode.getKind());
            assertEquals("<cross-parameter>", crossParameterNode.getName());
            assertFalse(nodes.hasNext());
        }
    }

    @Test
    void validatesExecutableGroupSequencesWithoutMicronautExecutableMetadata() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            Method method = SequenceExecutableBean.class.getDeclaredMethod("submit", String.class, String.class);
            SequenceExecutableBean bean = new SequenceExecutableBean();

            Set<ConstraintViolation<SequenceExecutableBean>> violations = validator.forExecutables()
                .validateParameters(bean, method, new Object[]{null, null}, ExecutableSequence.class);

            assertEquals(1, violations.size());
            assertEquals("submit.name", violations.iterator().next().getPropertyPath().toString());

            violations = validator.forExecutables()
                .validateParameters(bean, method, new Object[]{"name", null}, ExecutableSequence.class);

            assertEquals(2, violations.size());
            assertTrue(violations.stream().anyMatch(violation -> violation.getPropertyPath().toString().equals("submit.code")));
            assertTrue(violations.stream().anyMatch(violation -> violation.getPropertyPath().toString().equals("submit.<cross-parameter>")));
        }
    }

    @Test
    void validatesExecutableRedefinedDefaultGroupSequencesWithoutMicronautExecutableMetadata() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            Method method = RedefinedExecutableBean.class.getDeclaredMethod("submit", String.class, RedefinedAssociatedBean.class, String.class);
            RedefinedExecutableBean bean = new RedefinedExecutableBean();

            Set<ConstraintViolation<RedefinedExecutableBean>> violations = validator.forExecutables()
                .validateParameters(bean, method, new Object[]{null, new RedefinedAssociatedBean(null), null});

            assertEquals(2, violations.size());
            assertTrue(violations.stream().anyMatch(violation -> violation.getPropertyPath().toString().equals("submit.name")));
            assertTrue(violations.stream().anyMatch(violation -> violation.getPropertyPath().toString().equals("submit.associated.name")));

            violations = validator.forExecutables()
                .validateParameters(bean, method, new Object[]{"name", new RedefinedAssociatedBean(null), null});

            assertEquals(3, violations.size());
            assertTrue(violations.stream().anyMatch(violation -> violation.getPropertyPath().toString().equals("submit.associated.name")));
            assertTrue(violations.stream().anyMatch(violation -> violation.getPropertyPath().toString().equals("submit.code")));
            assertTrue(violations.stream().anyMatch(violation -> violation.getPropertyPath().toString().equals("submit.<cross-parameter>")));
        }
    }

    @Test
    void validatesReturnValueWithoutMicronautExecutableMetadata() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            PlainBean bean = new PlainBean("x");
            String returnValue = "";

            Set<ConstraintViolation<PlainBean>> violations = validator.forExecutables()
                .validateReturnValue(bean, PlainBean.class.getDeclaredMethod("displayName"), returnValue);

            assertEquals(1, violations.size());
            ConstraintViolation<PlainBean> violation = violations.iterator().next();
            assertEquals("", violation.getInvalidValue());
            assertNull(violation.getExecutableParameters());
            assertSame(returnValue, violation.getExecutableReturnValue());
            Iterator<Path.Node> nodes = violation.getPropertyPath().iterator();
            Path.Node methodNode = nodes.next();
            assertEquals(ElementKind.METHOD, methodNode.getKind());
            assertEquals("displayName", methodNode.getName());
            Path.Node returnValueNode = nodes.next();
            assertEquals(ElementKind.RETURN_VALUE, returnValueNode.getKind());
            assertEquals("<return value>", returnValueNode.getName());
            assertFalse(nodes.hasNext());
        }
    }

    @Test
    void validatesConstructorReturnValueExecutableMetadataWithoutMicronautExecutableMetadata() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            Constructor<ConstructorReturnBean> constructor = ConstructorReturnBean.class.getDeclaredConstructor();
            ConstructorReturnBean createdObject = new ConstructorReturnBean();

            Set<ConstraintViolation<ConstructorReturnBean>> violations = validator.forExecutables()
                .validateConstructorReturnValue(constructor, createdObject);

            assertEquals(1, violations.size());
            ConstraintViolation<ConstructorReturnBean> violation = violations.iterator().next();
            assertSame(createdObject, violation.getLeafBean());
            assertSame(createdObject, violation.getInvalidValue());
            assertNull(violation.getExecutableParameters());
            assertSame(createdObject, violation.getExecutableReturnValue());
        }
    }

    @Test
    void rejectsUnexpectedExecutableConstraintTypesWithoutMicronautExecutableMetadata() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            InvalidExecutableBean bean = new InvalidExecutableBean();

            assertThrows(ValidationException.class, () -> validator.forExecutables()
                .validateParameters(bean, InvalidExecutableBean.class.getDeclaredMethod("submit", String.class), new Object[]{"a"}));
        }
    }

    @Test
    void rejectsNullArgumentsWithIllegalArgumentException() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            assertThrows(IllegalArgumentException.class, () -> validator.validate(null));
            assertThrows(IllegalArgumentException.class, () -> validator.validateProperty(null, "name"));
            assertThrows(IllegalArgumentException.class, () -> validator.validateValue(null, "name", ""));
            assertThrows(IllegalArgumentException.class, () -> validator.getConstraintsForClass(null));
        }
    }

    @Test
    void instantiatesPrivateConstraintValidatorReflectively() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<PrivateConstraintBean>> violations = validator.validate(new PrivateConstraintBean("bad"));

            assertEquals(1, violations.size());
            assertEquals("name", violations.iterator().next().getPropertyPath().toString());
        }
    }

    @Test
    void readsPrivateRepeatableConstraintContainerReflectively() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<RepeatablePrivateConstraintBean>> violations = validator.validate(new RepeatablePrivateConstraintBean("bad"));

            assertEquals(2, violations.size());
        }
    }

    @Test
    void doesNotDuplicateGeneratedConstraintsWithReflectionMetadata() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<IntrospectedConstraintBean>> violations = validator.validate(new IntrospectedConstraintBean(false));

            assertEquals(1, violations.size());
            ConstraintViolation<IntrospectedConstraintBean> violation = violations.iterator().next();
            assertEquals("enabled", violation.getPropertyPath().toString());
            assertEquals(false, violation.getInvalidValue());
        }
    }

    @Test
    void resolvesReflectiveConstraintValidatorForTargetType() {
        ReflectionConstraintValidatorFactory factory = new ReflectionConstraintValidatorFactory(
            new DefaultInternalConstraintValidatorFactory(BeanIntrospector.SHARED, null)
        );

        assertNotNull(factory.getInstance(PrivateConstraintValidator.class, String.class, ConstraintTarget.IMPLICIT));
    }

    @Test
    void supplementsGeneratedValidationWithInheritedTypeConstraints() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<InheritedTypeConstraintBean>> violations = validator.validate(new InheritedTypeConstraintBean());

            assertEquals(2, violations.size());
        }
    }

    @Test
    void keepsFieldAndGetterConstraintsOnTheSamePropertyDistinct() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<FieldAndGetterConstraintBean>> violations = validator.validate(new FieldAndGetterConstraintBean(10));

            assertEquals(2, violations.size());
        }
    }

    @Test
    void usesFieldValueForFieldConstraintWhenGetterUsesDifferentValue() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            ValueRecordingValidator.values.clear();
            BeanValueRecordingValidator.value = null;
            FieldAndGetterValueBean bean = new FieldAndGetterValueBean();

            assertEquals(0, validator.validate(bean).size());

            assertEquals(List.of("field", "getter"), ValueRecordingValidator.values);
            assertSame(bean, BeanValueRecordingValidator.value);
        }
    }

    @Test
    void validatePropertyUsesConstrainedFieldValueWhenGetterIsUnconstrained() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            FieldAccessChild bean = new FieldAccessChild();

            assertEquals(0, validator.validateProperty(bean, "name").size());

            bean.name = null;
            assertEquals(1, validator.validateProperty(bean, "name").size());
        }
    }

    @Test
    void avoidsCircularGraphValidationThroughCascadedContainers() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            CyclicActorList clint = new CyclicActorList("Eastwood");
            CyclicActorList morgan = new CyclicActorList(null);
            CyclicActorList charlie = new CyclicActorList("Sheen");
            clint.addPlayedWith(charlie);
            charlie.addPlayedWith(clint);
            charlie.addPlayedWith(morgan);
            morgan.addPlayedWith(charlie);
            morgan.addPlayedWith(clint);
            clint.addPlayedWith(morgan);

            Set<ConstraintViolation<CyclicActorList>> violations = validator.validate(clint);

            assertEquals(2, violations.size());
        }
    }

    @Test
    void usesObjectArrayAsCascadedArrayContainerClass() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            CyclicActorArray clint = new CyclicActorArray("Eastwood");
            CyclicActorArray morgan = new CyclicActorArray(null);
            CyclicActorArray charlie = new CyclicActorArray("Sheen");
            clint.addPlayedWith(charlie);
            charlie.addPlayedWith(clint);
            charlie.addPlayedWith(morgan);
            morgan.addPlayedWith(charlie);
            morgan.addPlayedWith(clint);
            clint.addPlayedWith(morgan);

            Set<ConstraintViolation<CyclicActorArray>> violations = validator.validate(clint);

            assertEquals(2, violations.size());
            for (ConstraintViolation<CyclicActorArray> violation : violations) {
                for (Path.Node node : violation.getPropertyPath()) {
                    if (node.isInIterable()) {
                        assertEquals(Object[].class, node.as(Path.PropertyNode.class).getContainerClass());
                    }
                }
            }
        }
    }

    @Test
    void doesNotDuplicateLegacyAndTypeArgumentCascadedListValidation() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<LegacyAndTypeArgumentListBean>> violations = validator.validate(new LegacyAndTypeArgumentListBean());

            assertEquals(1, violations.size());
        }
    }

    @Test
    void usesNullTypeArgumentIndexForLegacyRawListCascading() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            ConstraintViolation<LegacyRawListBean> violation = validator.validate(new LegacyRawListBean()).iterator().next();
            Path.Node nameNode = null;
            for (Path.Node node : violation.getPropertyPath()) {
                nameNode = node;
            }

            assertNotNull(nameNode);
            assertEquals("name", nameNode.getName());
            assertNull(nameNode.as(Path.PropertyNode.class).getTypeArgumentIndex());
        }
    }

    @Test
    void appliesNestedContainerGroupConversionOnGetterProperty() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<GetterGroupConversionBean>> violations = validator.validate(new GetterGroupConversionBean());

            assertEquals(2, violations.size());
        }
    }

    @Test
    void appliesRedefinedDefaultGroupSequenceToSupplementalReflectionConstraints() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            DefaultGroupSequenceBean bean = new DefaultGroupSequenceBean("A");

            assertEquals(1, validator.validate(bean).size());
            assertEquals(1, validator.validateProperty(bean, "name").size());
            assertEquals(1, validator.validateValue(DefaultGroupSequenceBean.class, "name", "A").size());
            assertEquals(
                1,
                validator.getConstraintsForClass(DefaultGroupSequenceBean.class)
                    .getConstraintsForProperty("name")
                    .getConstraintDescriptors()
                    .size()
            );
        }
    }

    @Test
    void appliesImplicitInterfaceGroupsToSupplementalReflectionConstraints() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);
            ImplicitGroupOrder order = new ImplicitGroupOrder();

            assertEquals(5, validator.validate(order).size());
            assertEquals(4, validator.validate(order, Auditable.class).size());
        }
    }

    @Test
    void validatesReflectiveComposedConstraintAsSingleViolation() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            ReflectionValidator.WARNINGS_ENABLED, false
        ))) {
            Validator validator = context.getBean(Validator.class);

            Set<ConstraintViolation<ComposedConstraintBean>> violations = validator.validate(new ComposedConstraintBean(null));

            assertEquals(1, violations.size());
            assertEquals(ComposedNotEmpty.class, violations.iterator().next().getConstraintDescriptor().getAnnotation().annotationType());

            violations = validator.validate(new ComposedConstraintBean(""));

            assertEquals(1, violations.size());
            assertEquals(ComposedNotEmpty.class, violations.iterator().next().getConstraintDescriptor().getAnnotation().annotationType());

            assertEquals(0, validator.validate(new ComposedConstraintBean("valid")).size());
        }
    }

    @Test
    void ignoredMetadataProviderSuppressesSupplementalReflectionDescriptorConstraints() {
        DefaultValidatorConfiguration configuration = new DefaultValidatorConfiguration();
        configuration.setMetadataProviders(List.of(new IgnoringMetadataProvider(IgnoredMetadataBean.class)));
        ReflectionValidator validator = new ReflectionValidator(configuration, false);

        BeanDescriptor descriptor = validator.getConstraintsForClass(IgnoredMetadataBean.class);

        assertFalse(descriptor.isBeanConstrained());
        assertFalse(descriptor.hasConstraints());
        assertTrue(descriptor.getConstraintDescriptors().isEmpty());
        assertTrue(descriptor.getConstrainedProperties().isEmpty());
    }

    @Test
    void ignoredMetadataProviderSuppressesReflectionDescriptorConstraintsWithoutIntrospection() {
        DefaultValidatorConfiguration configuration = new DefaultValidatorConfiguration();
        configuration.setMetadataProviders(List.of(new IgnoringMetadataProvider(PlainIgnoredMetadataBean.class)));
        ReflectionValidator validator = new ReflectionValidator(configuration, false);

        BeanDescriptor descriptor = validator.getConstraintsForClass(PlainIgnoredMetadataBean.class);

        assertFalse(descriptor.isBeanConstrained());
        assertFalse(descriptor.hasConstraints());
        assertTrue(descriptor.getConstraintDescriptors().isEmpty());
        assertTrue(descriptor.getConstrainedProperties().isEmpty());
    }

    @Test
    void ignoredPropertyMetadataProviderSuppressesSupplementalReflectionValidation() {
        DefaultValidatorConfiguration configuration = new DefaultValidatorConfiguration();
        configuration.setMetadataProviders(List.of(new IgnoringPropertyMetadataProvider(PropertyIgnoredValidationBean.class, "name")));
        ReflectionValidator validator = new ReflectionValidator(configuration, false);

        Set<ConstraintViolation<PropertyIgnoredValidationBean>> violations = validator.validate(new PropertyIgnoredValidationBean());

        assertTrue(violations.isEmpty());
    }

    static final class PlainBean {
        @NotBlank
        private final String name;

        PlainBean(@NotBlank String name) {
            this.name = name;
        }

        @NotBlank
        String displayName() {
            return name;
        }
    }

    private static class BaseService {
        @CrossParameterConstraint
        void submit(@NotNull String name) {
        }
    }

    private static final class ChildService extends BaseService {
        @Override
        void submit(String name) {
        }
    }

    private static final class CrossParameterConstructorBean {
        @CrossParameterConstraint
        CrossParameterConstructorBean(String name) {
        }
    }

    private static final class ConstructorReturnBean {
        @InvalidConstructorReturnConstraint
        ConstructorReturnBean() {
        }
    }

    private static final class InvalidExecutableBean {
        public void submit(@InvalidIntegerConstraint String value) {
        }
    }

    private static final class SequenceExecutableBean {
        @CrossParameterConstraint(groups = ExecutableAdvanced.class)
        void submit(@NotNull(groups = ExecutableBasic.class) String name, @NotNull(groups = ExecutableAdvanced.class) String code) {
        }
    }

    private interface ExecutableBasic {
    }

    private interface ExecutableAdvanced {
    }

    @GroupSequence({ExecutableBasic.class, ExecutableAdvanced.class})
    private interface ExecutableSequence {
    }

    @GroupSequence({ExecutableBasic.class, RedefinedExecutableBean.class})
    private static final class RedefinedExecutableBean {
        @CrossParameterConstraint
        void submit(
            @NotNull(groups = ExecutableBasic.class) String name,
            @Valid RedefinedAssociatedBean associated,
            @NotNull String code) {
        }
    }

    private record RedefinedAssociatedBean(
        @NotNull String name
    ) {
    }

    @Target({METHOD, CONSTRUCTOR})
    @Retention(RUNTIME)
    @Constraint(validatedBy = CrossParameterConstraintValidator.class)
    private @interface CrossParameterConstraint {
        String message() default "invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @SupportedValidationTarget(ValidationTarget.PARAMETERS)
    private static final class CrossParameterConstraintValidator implements ConstraintValidator<CrossParameterConstraint, Object[]> {
        @Override
        public boolean isValid(Object[] value, ConstraintValidatorContext context) {
            return false;
        }
    }

    @Target({PARAMETER, METHOD, CONSTRUCTOR})
    @Retention(RUNTIME)
    @Constraint(validatedBy = InvalidIntegerConstraintValidator.class)
    private @interface InvalidIntegerConstraint {
        String message() default "invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    private static final class InvalidIntegerConstraintValidator implements ConstraintValidator<InvalidIntegerConstraint, Integer> {
        @Override
        public boolean isValid(Integer value, ConstraintValidatorContext context) {
            return false;
        }
    }

    @Target(CONSTRUCTOR)
    @Retention(RUNTIME)
    @Constraint(validatedBy = InvalidConstructorReturnConstraintValidator.class)
    private @interface InvalidConstructorReturnConstraint {
        String message() default "invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    private static final class InvalidConstructorReturnConstraintValidator implements ConstraintValidator<InvalidConstructorReturnConstraint, ConstructorReturnBean> {
        @Override
        public boolean isValid(ConstructorReturnBean value, ConstraintValidatorContext context) {
            return false;
        }
    }

    private record PrivateConstraintBean(
        @PrivateConstraint String name
    ) {
    }

    @Target(FIELD)
    @Retention(RUNTIME)
    @Repeatable(PrivateConstraints.class)
    @Constraint(validatedBy = PrivateConstraintValidator.class)
    private @interface PrivateConstraint {
        String message() default "invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @Target(FIELD)
    @Retention(RUNTIME)
    private @interface PrivateConstraints {
        PrivateConstraint[] value();
    }

    private static final class PrivateConstraintValidator implements ConstraintValidator<PrivateConstraint, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return false;
        }
    }

    private record RepeatablePrivateConstraintBean(
        @PrivateConstraint
        @PrivateConstraint
        String name
    ) {
    }

    @Introspected
    private record IntrospectedConstraintBean(
        @AssertTrue boolean enabled
    ) {
    }

    @Target(TYPE)
    @Retention(RUNTIME)
    @Constraint(validatedBy = InvalidTypeConstraintValidator.class)
    private @interface InvalidTypeConstraint {
        String message() default "invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    private static final class InvalidTypeConstraintValidator implements ConstraintValidator<InvalidTypeConstraint, Object> {
        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            return false;
        }
    }

    @InvalidTypeConstraint
    private interface TypeConstraintInterface {
    }

    @Introspected
    @InvalidTypeConstraint
    private static class TypeConstraintBase implements TypeConstraintInterface {
    }

    @Introspected
    private static final class InheritedTypeConstraintBean extends TypeConstraintBase {
    }

    @Introspected
    private static final class FieldAndGetterConstraintBean {
        @Max(5)
        private final int amount;

        private FieldAndGetterConstraintBean(int amount) {
            this.amount = amount;
        }

        @Max(5)
        public int getAmount() {
            return amount;
        }
    }

    @Introspected
    @BeanValueRecording
    private static final class FieldAndGetterValueBean {
        @ValueRecording
        private final String name = "field";

        @ValueRecording
        public String getName() {
            return "getter";
        }
    }

    @Target(TYPE)
    @Retention(RUNTIME)
    @Constraint(validatedBy = BeanValueRecordingValidator.class)
    private @interface BeanValueRecording {
        String message() default "invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    private static final class BeanValueRecordingValidator implements ConstraintValidator<BeanValueRecording, FieldAndGetterValueBean> {
        private static FieldAndGetterValueBean value;

        @Override
        public boolean isValid(FieldAndGetterValueBean value, ConstraintValidatorContext context) {
            BeanValueRecordingValidator.value = value;
            return true;
        }
    }

    @Target({FIELD, METHOD})
    @Retention(RUNTIME)
    @Constraint(validatedBy = ValueRecordingValidator.class)
    private @interface ValueRecording {
        String message() default "invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    private static final class ValueRecordingValidator implements ConstraintValidator<ValueRecording, String> {
        private static final List<String> values = new ArrayList<>();

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            values.add(value);
            return true;
        }
    }

    @Introspected
    private static class FieldAccessBase {
        @NotNull
        String name = "Lois";
    }

    @Introspected
    private static final class FieldAccessChild extends FieldAccessBase {
        public String getName() {
            return null;
        }
    }

    private abstract static class CyclicActor {
        @NotNull
        private final String lastName;

        private CyclicActor(String lastName) {
            this.lastName = lastName;
        }
    }

    private static final class CyclicActorList extends CyclicActor {
        @Valid
        private final List<CyclicActorList> playedWith = new ArrayList<>();

        private CyclicActorList(String lastName) {
            super(lastName);
        }

        private void addPlayedWith(CyclicActorList actor) {
            playedWith.add(actor);
        }
    }

    private static final class CyclicActorArray extends CyclicActor {
        @Valid
        private final CyclicActorArray[] playedWith = new CyclicActorArray[4];
        private int current;

        private CyclicActorArray(String lastName) {
            super(lastName);
        }

        private void addPlayedWith(CyclicActorArray actor) {
            playedWith[current++] = actor;
        }
    }

    private static final class LegacyAndTypeArgumentListBean {
        @Valid
        private final List<@Valid InvalidVisitor> visitors = List.of(new InvalidVisitor());
    }

    private static final class LegacyRawListBean {
        @Valid
        private final RawVisitorList visitors = new RawVisitorList();
    }

    private static final class RawVisitorList extends ArrayList<InvalidVisitor> {
        private RawVisitorList() {
            add(new InvalidVisitor());
        }
    }

    private static final class InvalidVisitor {
        @NotNull
        private final String name = null;
    }

    private interface PostalGroup {
    }

    @Introspected
    private static final class GetterGroupConversionBean {
        private final Map<String, List<GroupConversionAddress>> addresses = new HashMap<>();

        private GetterGroupConversionBean() {
            addresses.put("main", List.of(new GroupConversionAddress()));
        }

        Map<String, List<@Valid @ConvertGroup(from = Default.class, to = PostalGroup.class) GroupConversionAddress>> getAddresses() {
            return addresses;
        }
    }

    private static final class GroupConversionAddress {
        @NotNull(groups = PostalGroup.class)
        private final String street = null;

        @Size(min = 3, groups = PostalGroup.class)
        private final String zipCode = "1";
    }

    @Introspected
    @GroupSequence({DefaultGroupSequenceBean.class, Second.class})
    private static final class DefaultGroupSequenceBean {
        @Size(min = 2, groups = Second.class)
        private final String name;

        private DefaultGroupSequenceBean(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    private interface Second {
    }

    private interface Auditable {
        @NotNull
        String getCreationDate();

        @NotNull
        String getLastUpdate();

        @NotNull
        String getLastModifier();

        @NotNull
        String getLastReader();
    }

    @Introspected
    private static final class ImplicitGroupOrder implements Auditable {

        @Override
        public String getCreationDate() {
            return null;
        }

        @Override
        public String getLastUpdate() {
            return null;
        }

        @Override
        public String getLastModifier() {
            return null;
        }

        @Override
        public String getLastReader() {
            return null;
        }

        @NotNull
        public String getOrderNumber() {
            return null;
        }
    }

    private record ComposedConstraintBean(
        @ComposedNotEmpty String name
    ) {
    }

    @Introspected
    @InvalidTypeConstraint
    private static final class IgnoredMetadataBean {
        @Max(20)
        private int amount;

        public int getAmount() {
            return amount;
        }
    }

    private static final class PlainIgnoredMetadataBean {
        @Max(20)
        private int amount;
    }

    @Introspected
    private static final class PropertyIgnoredValidationBean {
        @NotNull
        private String name;

        public String getName() {
            return name;
        }
    }

    private record IgnoringMetadataProvider(Class<?> ignoredType) implements ValidationMetadataProvider {

        @Override
        public Optional<BeanDescriptor> getConstraintsForClass(Class<?> beanType) {
            return beanType == ignoredType ? Optional.of(new EmptyTestBeanDescriptor(beanType)) : Optional.empty();
        }

        @Override
        public AnnotationMetadata getBeanAnnotationMetadata(Class<?> beanType) {
            return AnnotationMetadata.EMPTY_METADATA;
        }

        @Override
        public boolean isBeanAnnotationMetadataIgnored(Class<?> beanType) {
            return beanType == ignoredType;
        }

        @Override
        public boolean isPropertyAnnotationMetadataIgnored(Class<?> beanType, String propertyName) {
            return beanType == ignoredType;
        }
    }

    private record IgnoringPropertyMetadataProvider(
        Class<?> ignoredType,
        String ignoredProperty
    ) implements ValidationMetadataProvider {

        @Override
        public Optional<BeanDescriptor> getConstraintsForClass(Class<?> beanType) {
            return Optional.empty();
        }

        @Override
        public boolean isPropertyAnnotationMetadataIgnored(Class<?> beanType, String propertyName) {
            return beanType == ignoredType && propertyName.equals(ignoredProperty);
        }
    }

    private record EmptyTestBeanDescriptor(Class<?> elementClass) implements BeanDescriptor, ElementDescriptor.ConstraintFinder {

        @Override
        public boolean isBeanConstrained() {
            return false;
        }

        @Override
        public PropertyDescriptor getConstraintsForProperty(String propertyName) {
            return null;
        }

        @Override
        public Set<PropertyDescriptor> getConstrainedProperties() {
            return Set.of();
        }

        @Override
        public MethodDescriptor getConstraintsForMethod(String methodName, Class<?>... parameterTypes) {
            return null;
        }

        @Override
        public Set<MethodDescriptor> getConstrainedMethods(MethodType methodType, MethodType... methodTypes) {
            return Set.of();
        }

        @Override
        public ConstructorDescriptor getConstraintsForConstructor(Class<?>... parameterTypes) {
            return null;
        }

        @Override
        public Set<ConstructorDescriptor> getConstrainedConstructors() {
            return Set.of();
        }

        @Override
        public boolean hasConstraints() {
            return false;
        }

        @Override
        public Class<?> getElementClass() {
            return elementClass;
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return Set.of();
        }

        @Override
        public ConstraintFinder findConstraints() {
            return this;
        }

        @Override
        public ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return this;
        }

        @Override
        public ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }
    }

    @Target(FIELD)
    @Retention(RUNTIME)
    @Constraint(validatedBy = {})
    @ReportAsSingleViolation
    @NotNull
    @Size
    private @interface ComposedNotEmpty {
        String message() default "empty";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};

        @OverridesAttribute(constraint = Size.class, name = "min")
        int min() default 5;
    }
}
