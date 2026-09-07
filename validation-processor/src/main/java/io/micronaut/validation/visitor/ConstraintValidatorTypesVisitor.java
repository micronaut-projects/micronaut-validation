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
package io.micronaut.validation.visitor;

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.beans.visitor.IntrospectedTypeElementVisitor;
import io.micronaut.validation.annotation.ConstraintValidatorTypes;
import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.Set;

/**
 * Records the types an introspected {@link jakarta.validation.ConstraintValidator} implementation binds -
 * the constraint and the validated type - in its introspection, as {@link ConstraintValidatorTypes}, so that
 * the validator reads them from the generated metadata rather than from the generic signature of the class.
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
public final class ConstraintValidatorTypesVisitor implements TypeElementVisitor<Object, Object> {

    private static final String CONSTRAINT_VALIDATOR = "jakarta.validation.ConstraintValidator";

    @Override
    public int getOrder() {
        // before the introspection visitor, so that the annotation is part of the introspection
        return IntrospectedTypeElementVisitor.POSITION + 10;
    }

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of("io.micronaut.core.annotation.Introspected");
    }

    @NonNull
    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element.isAbstract() || element.isInterface() || !element.isAssignable(CONSTRAINT_VALIDATOR)) {
            return;
        }
        Map<String, ClassElement> typeArguments = element.getTypeArguments(CONSTRAINT_VALIDATOR);
        ClassElement constraint = typeArguments.get("A");
        ClassElement target = typeArguments.get("T");
        if (constraint == null || target == null || unresolved(constraint) || unresolved(target)) {
            return;
        }
        element.annotate(ConstraintValidatorTypes.class, builder -> builder
            .member("constraint", new AnnotationClassValue<>(constraint.getName()))
            .member("target", new AnnotationClassValue<>(target.getName())));
    }

    /**
     * Whether a type argument is a variable the implementation leaves open - {@code AnyValidator<T> implements
     * ConstraintValidator<NotNull, T>} - as opposed to a variable it binds, which is reported as a placeholder
     * resolved to the bound type.
     */
    private static boolean unresolved(ClassElement typeArgument) {
        return typeArgument instanceof GenericPlaceholderElement placeholder && placeholder.getResolved().isEmpty();
    }
}
