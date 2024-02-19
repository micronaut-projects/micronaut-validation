/*
 * Copyright 2017-2024 original authors
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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Vetoed;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.ast.annotation.MutableAnnotationMetadataDelegate;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.validation.RequiresValidation;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * The visitor creates annotations utilized by the Validator.
 * It adds @RequiresValidation annotation to fields if they require validation, and to methods
 * if one of the parameters or return value require validation.
 *
 * @author Denis Stepanov
 * @since 4.0.0
 */
@Internal
public class ValidationVisitor implements TypeElementVisitor<Object, Object> {

    private static final String ANN_CASCADE = "io.micronaut.validation.annotation.ValidatedElement";
    private static final String ANN_CONSTRAINT = "jakarta.validation.Constraint";
    private static final String ANN_VALID = "jakarta.validation.Valid";

    private ClassElement classElement;
    private final Set<Object> visited = new HashSet<>();

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of("jakarta.validation.*");
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
        visited.clear();
        classElement = element;
        if (classElement.isInterface() && classElement.hasAnnotation("jakarta.validation.GroupSequence")) {
            classElement.annotate(Introspected.class);
        }
        classElement.getMethods().forEach(m -> visitMethod(m, context));
    }

    @Override
    public void visitConstructor(ConstructorElement element, VisitorContext context) {
        if (classElement == null) {
            return;
        }
        if (!visited.add(element)) {
            return;
        }
        boolean parametersRequireValidation = parametersRequireValidation(element, true);
        boolean returnTypeRequiresValidation = visitElementValidationAndMarkForValidationIfNeeded(element.getReturnType(), true);
        if (returnTypeRequiresValidation || parametersRequireValidation) {
            element.annotate(RequiresValidation.class);
            classElement.annotate(RequiresValidation.class);
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (classElement == null || element.hasStereotype(Vetoed.class)) {
            return;
        }
        if (!visited.add(element)) {
            return;
        }

        element.getOverriddenMethods().forEach(m -> inheritAnnotationsForMethod(element, m));

        boolean isPrivate = element.isPrivate();
        boolean isAbstract = element.getOwningType().isInterface() || element.getOwningType().isAbstract();
        boolean requireOnConstraint = isAbstract || !isPrivate;

        boolean parametersRequireValidation = parametersRequireValidation(element, requireOnConstraint);
        boolean returnTypeRequiresValidation = visitElementValidationAndMarkForValidationIfNeeded(element.getReturnType(), requireOnConstraint);
        boolean methodAnnotatedForValidation = returnTypeRequiresValidation(element, true);
        if (parametersRequireValidation || returnTypeRequiresValidation || methodAnnotatedForValidation) {
            if (isPrivate) {
                throw new ProcessingException(element, "Method annotated for validation but is declared private. Change the method to be non-private in order for AOP advice to be applied.");
            } else {
                element.annotate(RequiresValidation.class);
                classElement.annotate(RequiresValidation.class);
            }
        }
    }

    @Override
    public void visitField(FieldElement element, VisitorContext context) {
        if (classElement == null) {
            return;
        }
        if (!visited.add(element)) {
            return;
        }
        if (visitElementValidationAndMarkForValidationIfNeeded(element, true)) {
            element.annotate(RequiresValidation.class);
            classElement.annotate(RequiresValidation.class);
        }
    }

    private boolean parametersRequireValidation(MethodElement element, boolean requireOnConstraint) {
        boolean requiredValidation = false;
        for (ParameterElement parameter : element.getParameters()) {
            // Make sure `visitElementValidationAndMarkForValidationIfNeeded` is invoked for all parameters to mark it of cascading
            boolean requiresValidationForParameter = visitElementValidationAndMarkForValidationIfNeeded(parameter, requireOnConstraint);
            requiredValidation |= requiresValidationForParameter;
        }
        return requiredValidation;
    }

    private boolean returnTypeRequiresValidation(MethodElement e, boolean requireOnConstraint) {
        MutableAnnotationMetadataDelegate<AnnotationMetadata> methodAnnotationMetadata = e.getMethodAnnotationMetadata();
        return methodAnnotationMetadata.hasStereotype(ANN_VALID) || (requireOnConstraint && methodAnnotationMetadata.hasStereotype(ANN_CONSTRAINT));
    }

    private boolean visitElementValidationAndMarkForValidationIfNeeded(TypedElement e, boolean requireOnConstraint) {
        boolean requiresTypeValidation = visitTypedElementValidationAndMarkForValidationIfNeeded(e, requireOnConstraint);

        AnnotationMetadata annotationMetadata = e instanceof ClassElement ce ? ce.getTypeAnnotationMetadata() : e.getAnnotationMetadata();
        boolean requiresValidation = (requireOnConstraint && annotationMetadata.hasStereotype(ANN_CONSTRAINT))
            || annotationMetadata.hasStereotype(ANN_VALID)
            || requiresTypeValidation;
        if (requiresValidation) {
            try {
                e.annotate(ANN_CASCADE);
                e.annotate(RequiresValidation.class);
            } catch (IllegalStateException ex) {
                // workaround Groovy bug
            }
        }
        return requiresValidation;
    }

    private boolean visitTypedElementValidationAndMarkForValidationIfNeeded(TypedElement e, boolean requireOnConstraint) {
        boolean requires = false;
        ClassElement genericType = e.getGenericType();
        for (ClassElement typeArgument : genericType.getTypeArguments().values()) {
            // Make sure `visitElementValidationAndMarkForValidationIfNeeded` is invoked on all type arguments to mark it of cascading
            boolean requiresForType = visitElementValidationAndMarkForValidationIfNeeded(typeArgument, requireOnConstraint);
            requires |= requiresForType;
        }
        if (!genericType.equals(e)) {
            requires |= visitElementValidationAndMarkForValidationIfNeeded(genericType, requireOnConstraint);
        }
        return requires;
    }

    /**
     * Method that makes sure that all the annotations are inherited from parent.
     * In particular, type arguments annotations are not inherited by default.
     */
    private void inheritAnnotationsForMethod(MethodElement method, MethodElement parent) {
        ParameterElement[] methodParameters = method.getParameters();
        ParameterElement[] parentParameters = parent.getParameters();

        for (int i = 0; i < methodParameters.length; ++i) {
            inheritAnnotationsForParameter(methodParameters[i], parentParameters[i]);
        }
        inheritAnnotationsForParameter(method.getReturnType(), parent.getReturnType());
    }

    /**
     * Method that makes sure that all the annotations are inherited from parent.
     * In particular, type arguments annotations are not inherited by default.
     */
    private void inheritAnnotationsForParameter(TypedElement element, TypedElement parentElement) {
        if (!element.getType().equals(parentElement.getType())) {
            return;
        }
        Stream<String> parentAnnotations = Stream.concat(
            parentElement.getAnnotationNamesByStereotype(ANN_CONSTRAINT).stream(),
            parentElement.getAnnotationNamesByStereotype(ANN_VALID).stream()
        );
        parentAnnotations
            .filter(name -> !element.hasAnnotation(name))
            .flatMap(name -> parentElement.getAnnotationValuesByName(name).stream())
            .forEach(element::annotate);

        Map<String, ClassElement> typeArguments = element.getGenericType().getTypeArguments();
        Map<String, ClassElement> parentTypeArguments = parentElement.getGenericType().getTypeArguments();
        if (typeArguments.size() != parentTypeArguments.size()) {
            return;
        }
        for (var entry : typeArguments.entrySet()) {
            inheritAnnotationsForParameter(entry.getValue(), parentTypeArguments.get(entry.getKey()));
        }
    }
}
