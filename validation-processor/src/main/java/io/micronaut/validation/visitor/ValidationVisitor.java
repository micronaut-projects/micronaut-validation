/*
 * Copyright 2017-2020 original authors
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


import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.validation.RequiresValidation;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.Arrays;
import java.util.Set;

/**
 * The visitor creates annotations utilized by the Validator.
 * <p>
 * It adds @RequiresValidation annotation to fields if they require validation, and to methods
 * if one of the parameters or return value require validation.
 *
 * @author Denis Stepanov
 * @since 4.0.0
 */
@Internal
public class ValidationVisitor implements TypeElementVisitor<Object, Object> {

    private static final String ANN_CONSTRAINT = "javax.validation.Constraint";
    private static final String ANN_VALID = "javax.validation.Valid";

    private ClassElement classElement;

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of(ANN_CONSTRAINT, ANN_VALID);
    }

    @Override
    public int getOrder() {
        return 10; // Should run before ConfigurationReaderVisitor
    }

    @NonNull
    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        classElement = element;
    }

    @Override
    public void visitConstructor(ConstructorElement element, VisitorContext context) {
        if (classElement == null) {
            return;
        }
        if (requiresValidation(element.getReturnType(), true)
            || parametersRequireValidation(element, true)) {
            element.annotate(RequiresValidation.class);
            classElement.annotate(RequiresValidation.class);
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (classElement == null) {
            return;
        }
        boolean isPrivate = element.isPrivate();
        boolean isAbstract = element.getOwningType().isInterface() || element.getOwningType().isAbstract();
        boolean requireOnConstraint = isAbstract || !isPrivate;

        if (requiresValidation(element.getReturnType(), requireOnConstraint)
            || returnTypeRequiresValidation(element, true)
            || parametersRequireValidation(element, requireOnConstraint)) {
            if (isPrivate) {
                throw new ProcessingException(element, "Method annotated for validation but is declared private. Change the method to be non-private in order for AOP advice to be applied.");
            }
            element.annotate(RequiresValidation.class);
            classElement.annotate(RequiresValidation.class);
        }
    }

    @Override
    public void visitField(FieldElement element, VisitorContext context) {
        if (classElement == null) {
            return;
        }
        if (requiresValidation(element, true)) {
            element.annotate(RequiresValidation.class);
            classElement.annotate(RequiresValidation.class);
        }
    }

    private boolean parametersRequireValidation(MethodElement element, boolean requireOnConstraint) {
        return Arrays.stream(element.getParameters()).anyMatch(param -> requiresValidation(param, requireOnConstraint));
    }

    private boolean returnTypeRequiresValidation(MethodElement e, boolean requireOnConstraint) {
        return e.hasStereotype(ANN_VALID) || (requireOnConstraint && e.hasStereotype(ANN_CONSTRAINT));
    }

    private boolean requiresValidation(TypedElement e, boolean requireOnConstraint) {
        AnnotationMetadata annotationMetadata = e instanceof ClassElement ce ? ce.getTypeAnnotationMetadata() : e.getAnnotationMetadata();
        if (annotationMetadata.hasStereotype(ANN_VALID)) {
            // Annotate the element with same annotation that we annotate classes with.
            // This will ensure the correct behavior of io.micronaut.inject.ast.utils.AstBeanPropertiesUtils
            // in certain cases, as it relies on the fact that usages of types inherit
            // annotations from the type itself
            e.annotate(RequiresValidation.class);
        }
        return (requireOnConstraint && annotationMetadata.hasStereotype(ANN_CONSTRAINT))
            || annotationMetadata.hasStereotype(ANN_VALID)
            || typeArgumentsRequireValidation(e, requireOnConstraint);
    }

    private boolean typeArgumentsRequireValidation(TypedElement e, boolean requireOnConstraint) {
        if (e instanceof GenericPlaceholderElement) {
            // To avoid infinite loops in case of circular generic dependency
            // For example, in case of A<? extends B>, B<? extends A>
            return false;
        }
        return e.getGenericType().getTypeArguments().values().stream().anyMatch(classElement -> requiresValidation(classElement, requireOnConstraint));
    }
}
