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
package io.micronaut.validation.validator;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.core.beans.BeanIntrospector;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedDeclarationAccessTest {
    private final Validator validator = Validator.getInstance();

    @Test
    void unrelatedMethodsAreNotMadeExecutable() {
        var methods = BeanIntrospector.SHARED.getIntrospection(SelectiveExecutables.class).getBeanMethods();
        assertTrue(methods.stream().anyMatch(method -> method.getName().equals("constrained")));
        assertTrue(methods.stream().anyMatch(method -> method.getName().equals("explicit")));
        assertFalse(methods.stream().anyMatch(method -> method.getName().equals("unrelated")));
    }

    @Introspected
    static class SelectiveExecutables {
        @NotNull public String constrained() { return "valid"; }
        @io.micronaut.context.annotation.Executable public String explicit() { return "valid"; }
        public String unrelated() { return "valid"; }
    }

    @Test
    void defaultPropertyAccessUsesTheGetterWithoutReflectivePermission() {
        var bean = new StandardBean();
        assertEquals(1, validator.validate(bean).size());
        assertEquals(1, bean.reads);
        bean.value = "valid";
        assertTrue(validator.validate(bean).isEmpty());
        assertEquals(2, bean.reads);
    }

    @Test
    void recordComponentsUseGeneratedAccessorsWithoutReflectivePermission() {
        assertEquals(2, validator.validate(new StandardRecord(null, java.util.Arrays.asList((String) null))).size());
        assertTrue(validator.validate(new StandardRecord("valid", java.util.List.of("valid"))).isEmpty());
        assertEquals(1, validator.validate(new FieldRecord(null)).size());
    }

    @Test
    void propertyAccessDoesNotEraseADifferentlyTypedFieldDeclaration() {
        assertThrows(ValidationException.class, () -> validator.validate(new DifferentlyTypedGetter()));
    }

    @Test
    void fieldAndGetterReadTheirOwnValuesAndKeepRepeatedConstraints() {
        assertEquals(2, validator.validate(new DifferentValues()).size());
        assertEquals(1, validator.validate(new DifferentValues("field", null)).size());
        assertEquals(1, validator.validate(new DifferentValues(null, "getter")).size());
    }

    @Test
    void annotatedPrivateFieldStillRequiresTheReflectionModule() {
        var error = assertThrows(ValidationException.class, () -> validator.validate(new Authorized()));
        assertTrue(error.getCause().getMessage().contains("micronaut-validation-reflection"));
    }

    @Test
    void privateFieldWithoutPermissionFailsEvenWithAnyVisibilityAndTypeAnnotation() {
        ValidationException error = assertThrows(ValidationException.class, () -> validator.validate(new Unauthorized()));
        assertTrue(error.getCause().getMessage().contains("micronaut-validation-reflection"));
    }

    @Test
    void privateMethodValidationRequiresTheOptionalProvider() throws Exception {
        var bean = new PrivateExecutable();
        var method = PrivateExecutable.class.getDeclaredMethod("process", String.class);
        var failure = assertThrows(ValidationException.class,
            () -> validator.forExecutables().validateParameters(bean, method, new Object[]{null}));
        assertTrue(failure.getMessage().contains("micronaut-validation-reflection"));
    }

    @Test
    void overridingGetterDoesNotRepeatAnInheritedDeclaration() {
        assertEquals(1, validator.validate(new Child()).size());
    }

    @Test
    void generatedContainerBindingsKeepNestedTypesAndAnnotations() {
        var bound = new CompileTimeSupport().boundTypeArgument(Concrete.class, Container.class, 0);
        assertNotNull(bound);
        assertEquals(String.class, bound.getTypeParameters()[0].getType());
        assertEquals(1, bound.getTypeParameters()[0].getAnnotationMetadata().getAnnotationValuesByType(NotNull.class).size());
    }

    @Test
    void generatedContainerMappingsFollowReorderedVariables() {
        var support = new CompileTimeSupport();
        assertEquals(1, support.extractedTypeArgumentIndex(Swapped.class, Pair.class, 0));
        assertEquals(0, support.extractedTypeArgumentIndex(Swapped.class, Pair.class, 1));
        // a container declaring no constraint keeps its introspection as declared, the mappings recorded all the same
        assertFalse(BeanIntrospector.SHARED.getIntrospection(Swapped.class).separatesDeclarations());
    }

    @Test
    void inheritedContainerTypeUseConstraintsSurviveResolutionWithoutContaminatingOtherFields() {
        var bean = new InheritedContainer();
        bean.items = java.util.Arrays.asList((String) null);
        bean.plain = java.util.Arrays.asList((String) null);
        assertEquals(1, validator.validate(bean).size());
        var introspection = BeanIntrospector.SHARED.getIntrospection(InheritedContainer.class);
        assertEquals(String.class, introspection.getRequiredProperty("items", java.util.List.class).asArgument().getTypeParameters()[0].getType());
        assertFalse(introspection.getRequiredProperty("plain", java.util.List.class).asArgument().getTypeParameters()[0].getAnnotationMetadata().hasAnnotation(NotNull.class));
    }

    @Introspected
    static class PrivateExecutable {
        @NotNull
        @io.micronaut.core.annotation.Vetoed
        private java.util.List<@NotNull String> process(@NotNull String value) {
            throw new AssertionError("Validation must not invoke the supplied method");
        }
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static class ContainerParent<T> {
        public java.util.List<@NotNull T> items;
        public java.util.List<T> plain;
    }
    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static class InheritedContainer extends ContainerParent<String> { }

    @Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD})
    static class DifferentValues {
        @NotNull public String value;
        private String returned;
        DifferentValues() { this(null, null); }
        DifferentValues(String value, String returned) { this.value = value; this.returned = returned; }
        @NotNull public String getValue() { return returned; }
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD, visibility = Introspected.Visibility.ANY)
    static class Authorized {
        @NotNull @ReflectiveAccess private String value;
    }

    @ReflectiveAccess
    @Introspected(accessKind = Introspected.AccessKind.FIELD, visibility = Introspected.Visibility.ANY)
    static class Unauthorized {
        @NotNull private String value;
    }

    @Introspected
    static class Parent {
        @NotNull public String getValue() { return null; }
    }
    @Introspected
    static class Child extends Parent {
        @Override public String getValue() { return null; }
    }

    interface Container<E> { }
    static class Middle<T> implements Container<java.util.List<@NotNull T>> { }
    @Introspected static class Concrete extends Middle<String> { }
    interface Pair<A, B> { }
    @Introspected static class Swapped<X, Y> implements Pair<Y, X> { }

    @Introspected
    static class StandardBean {
        @NotNull private String value;
        private int reads;
        public String getValue() { reads++; return value; }
        public void setValue(String value) { this.value = value; }
    }

    @Introspected
    record StandardRecord(@NotNull String value, java.util.List<@NotNull String> items) { }

    @Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD})
    record FieldRecord(@NotNull String value) { }

    @Introspected
    static class DifferentlyTypedGetter {
        @NotNull private String value;
        public java.util.Optional<String> getValue() { return java.util.Optional.ofNullable(value); }
    }
}
