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

import io.micronaut.context.annotation.Executable;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
import io.micronaut.validation.validator.metadata.ValidationMetadataSupport;

import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.stream.Stream;

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

    private @Nullable ClassElement classElement;
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
        if (classElement == null) {
            return;
        }
        if (!visited.add(element)) {
            return;
        }
        if (!element.getOverriddenMethods().isEmpty()) {
            var declared = Stream.of(element.getParameters()).map(parameter -> declaredTypeUse(parameter, new HashSet<>()))
                .toArray(AnnotationValue<?>[]::new);
            element.annotate(ValidationMetadataSupport.DECLARED_PARAMETERS, builder -> builder.member("arguments", declared));
        }
        element.getOverriddenMethods().forEach(parent -> inheritAnnotationsForMethod(element, parent));

        boolean isPrivate = element.isPrivate();
        boolean isAbstract = element.getOwningType().isInterface() || element.getOwningType().isAbstract();
        boolean requireOnConstraint = isAbstract || !isPrivate;

        boolean parametersRequireValidation = parametersRequireValidation(element, requireOnConstraint);
        boolean returnTypeRequiresValidation = visitElementValidationAndMarkForValidationIfNeeded(element.getReturnType(), requireOnConstraint);
        boolean methodAnnotatedForValidation = returnTypeRequiresValidation(element, true);
        boolean inheritedValidation = element.getOverriddenMethods().stream().anyMatch(ValidationVisitor::requiresValidation);
        if (!parametersRequireValidation && !returnTypeRequiresValidation && !methodAnnotatedForValidation && !inheritedValidation) {
            return;
        }
        // a vetoed method is not validated when it is invoked: it asks for no validation advice. It is
        // described all the same - the specification describes every constrained method, whether or not
        // anything validates it - so being described and being validated are decided apart
        boolean vetoed = element.hasStereotype(Vetoed.class);
        if (isPrivate) {
            if (vetoed) {
                return;
            }
            throw new ProcessingException(element, "Method annotated for validation but is declared private. Change the method to be non-private in order for AOP advice to be applied.");
        }
        // the specification describes every constrained method: a bean method of the introspection
        // is what a MethodDescriptor is read from, and only an executable method becomes one
        element.annotate(Executable.class);
        if (classElement.isInterface() || classElement.isAbstract()) {
            classElement.annotate(Introspected.class);
        }
        if (vetoed) {
            return;
        }
        element.annotate(RequiresValidation.class);
        classElement.annotate(RequiresValidation.class);
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

    private static boolean requiresValidation(MethodElement method) {
        if (method.hasStereotype(ANN_CONSTRAINT) || method.hasStereotype(ANN_VALID)
            || hasValidation(method.getGenericReturnType(), new HashSet<>())) {
            return true;
        }
        for (ParameterElement parameter : method.getParameters()) {
            if (hasValidation(parameter, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasValidation(TypedElement element, Set<Object> visited) {
        AnnotationMetadata metadata = element instanceof ClassElement type ? type.getTypeAnnotationMetadata() : element.getAnnotationMetadata();
        if (metadata.hasStereotype(ANN_CONSTRAINT) || hasContainerConstraint(metadata) || metadata.hasStereotype(ANN_VALID)) {
            return true;
        }
        if (element instanceof ClassElement type && type.isPrimitive() || !visited.add(element.getNativeType())) {
            return false;
        }
        ClassElement generic = element.getGenericType();
        if (generic != element && hasValidation(generic, visited)) {
            return true;
        }
        return generic.getTypeArguments().values().stream().anyMatch(argument -> hasValidation(argument, visited));
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
        return methodAnnotationMetadata.hasStereotype(ANN_VALID)
            || (requireOnConstraint && (methodAnnotationMetadata.hasStereotype(ANN_CONSTRAINT) || hasContainerConstraint(methodAnnotationMetadata.getAnnotationMetadata())));
    }

    private boolean visitElementValidationAndMarkForValidationIfNeeded(TypedElement e, boolean requireOnConstraint) {
        boolean requiresTypeValidation = visitTypedElementValidationAndMarkForValidationIfNeeded(e, requireOnConstraint);

        AnnotationMetadata annotationMetadata = e instanceof ClassElement ce ? ce.getTypeAnnotationMetadata() : e.getAnnotationMetadata();
        boolean requiresValidation = (requireOnConstraint && (annotationMetadata.hasStereotype(ANN_CONSTRAINT) || hasContainerConstraint(annotationMetadata)))
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

    private static boolean hasContainerConstraint(AnnotationMetadata metadata) {
        return metadata.getAnnotationNames().stream().flatMap(name -> metadata.getAnnotationValuesByName(name).stream())
            .flatMap(value -> value.getAnnotations(AnnotationMetadata.VALUE_MEMBER).stream())
            .anyMatch(value -> value.contains(io.micronaut.validation.validator.ValidationAnnotationUtil.CONSTRAINT_TYPE));
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
        // Core merges method annotations itself. Only nested type-use annotations need
        // supplementation; copying the return declaration would make it local to an override.
        Map<String, ClassElement> local = method.getGenericReturnType().getTypeArguments();
        Map<String, ClassElement> inherited = parent.getGenericReturnType().getTypeArguments();
        for (var entry : local.entrySet()) {
            ClassElement argument = inherited.get(entry.getKey());
            if (argument != null) {
                inheritAnnotationsForParameter(entry.getValue(), argument);
            }
        }
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
            ClassElement parentTypeArgument = parentTypeArguments.get(entry.getKey());
            if (parentTypeArgument != null) {
                inheritAnnotationsForParameter(entry.getValue(), parentTypeArgument);
            }
        }
    }

    private static AnnotationValue<?> declaredTypeUse(TypedElement element, Set<Object> visited) {
        AnnotationMetadata metadata = element instanceof ClassElement type ? type.getTypeAnnotationMetadata() : element.getAnnotationMetadata();
        var result = AnnotationValue.builder(ValidationMetadataSupport.TYPE_USE)
            .member("annotations", metadata.getDeclaredAnnotationNames().stream()
                .flatMap(name -> metadata.getDeclaredAnnotationValuesByName(name).stream()).toArray(AnnotationValue<?>[]::new));
        if (visited.add(element.getNativeType())) {
            result.member("arguments", element.getGenericType().getTypeArguments().values().stream()
                .map(argument -> declaredTypeUse(argument, new HashSet<>(visited))).toArray(AnnotationValue<?>[]::new));
        }
        return result.build();
    }
}
