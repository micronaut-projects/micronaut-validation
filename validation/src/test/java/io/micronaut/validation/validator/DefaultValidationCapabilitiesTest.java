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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.validation.annotation.ValidatedElement;
import jakarta.validation.Valid;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultValidationCapabilitiesTest {
    @Test
    void legacyConstraintContainersRetainEveryOccurrenceAndTraversalMarkers() {
        try (var factory = new DefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertEquals(2, validator.getConstraintsForClass(Bundled.class).getConstraintsForProperty("value")
                .getConstraintDescriptors().size());
            assertEquals(2, validator.validate(new Bundled()).size());
        }
    }

    @Test
    void duplicateAndInheritedDefaultGroupsUseMetadataAndAssignability() {
        try (var factory = new DefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertEquals(1, validator.validate(new Child(null), Inherited.class, Inherited.class).size());
            assertEquals(0, validator.validate(new Parent(new Child(null)), Inherited.class).size());
            assertTrue(BeanIntrospector.SHARED.findIntrospection(Inherited.class).isPresent());
            assertEquals(0, validator.validate(new Parent(new Child(null)), GeneratedDefaultGroup.class).size());
            assertTrue(BeanIntrospector.SHARED.findIntrospection(GeneratedDefaultGroup.class).isPresent());
        }
    }

    @Test
    void unmarkedBranchesAreNotTraversedEvenWhenTheirDescendantsHaveConstraints() {
        var leafMetadata = new MutableAnnotationMetadata();
        leafMetadata.addDeclaredAnnotation(NotNull.class.getName(), Map.of());
        leafMetadata.addDeclaredAnnotation(Valid.class.getName(), Map.of());
        leafMetadata.addDeclaredAnnotation(ValidatedElement.class.getName(), Map.of());
        var leaf = Argument.of(String.class, "leaf", leafMetadata);
        var unmarked = Argument.of(List.class, "unmarked", AnnotationMetadata.EMPTY_METADATA, leaf);
        var root = Argument.of(List.class, "root", AnnotationMetadata.EMPTY_METADATA, unmarked);
        var support = ReflectionSupport.generated(BeanIntrospector.SHARED, getClass().getClassLoader());
        assertFalse(ArgumentValidationMetadata.isValidated(support, root));
        assertFalse(ArgumentValidationMetadata.hasValidatedTypeArgument(support, root));
        assertFalse(ArgumentValidationMetadata.hasCascadedTypeArgument(support, root));
        assertTrue(ArgumentValidationMetadata.hasValidatedTypeArgument(support, unmarked));
    }

    @Test
    void defaultClasspathHasNeitherReflectionNorAdditionalGenerationInfrastructure() {
        for (String type : List.of("io.micronaut.reflection.ReflectionArguments",
            "io.micronaut.validation.el.ElMessageInterpolator",
            "jakarta.el.ExpressionFactory",
            "io.micronaut.validation.reflection.ReflectionValidationSupport",
            "io.micronaut.validation.visitor.GeneratedAnnotationVisitor",
            "io.micronaut.validation.validator.metadata.GeneratedAnnotationProvider")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type));
        }
        try (var factory = new DefaultValidatorFactory()) {
            var descriptor = factory.getValidator().getConstraintsForClass(GeneratedDeclarationAccessTest.StandardRecord.class)
                .getConstraintsForProperty("value").getConstraintDescriptors().iterator().next();
            var failure = assertThrows(ValidationException.class, descriptor::getAnnotation);
            assertTrue(failure.getMessage().contains("micronaut-validation-reflection"));
        }
    }

    @Introspected
    interface Inherited extends Default { }
    interface Converted { }

    @Introspected
    record Child(@NotNull @Nullable String value) { }

    @Introspected
    record Parent(@Valid @ConvertGroup(to = Converted.class) Child child) { }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static class Bundled {
        @BundledConstraint.List({@BundledConstraint(message = "first"), @BundledConstraint(message = "second")})
        public String value;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @Constraint(validatedBy = {})
    @NotNull
    @ReportAsSingleViolation
    @interface BundledConstraint {
        String message() default "bundle";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.FIELD)
        @interface List {
            BundledConstraint[] value();
        }
    }
}
