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
import io.micronaut.inject.ast.AnnotationElement;
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
import io.micronaut.validation.validator.ValidationAnnotationUtil;
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

    /**
     * The annotation processor option making an invalid constraint definition a compilation error. Without
     * it the definition is reported as a warning and, as the specification asks, fails when it is validated.
     */
    public static final String STRICT_DEFINITIONS_OPTION = "micronaut.validation.strictConstraintDefinitions";

    private static final String ANN_CASCADE = "io.micronaut.validation.annotation.ValidatedElement";
    private static final String ANN_CONSTRAINT = "jakarta.validation.Constraint";
    private static final String ANN_VALID = "jakarta.validation.Valid";
    // a file name may be 255 bytes long; a builder adds two separators and "$AnnotationBuilder" to its two names
    private static final int MAX_FILE_NAME = 255;
    private static final int BUILDER_NAME_OVERHEAD = 20;

    private @Nullable ClassElement classElement;
    private final Set<Object> visited = new HashSet<>();
    private @Nullable VisitorContext visitorContext;
    private final Set<String> importedTypes = new HashSet<>();
    private final Set<String> registeredBuilders = new HashSet<>();
    private @Nullable Set<String> shippedBuilders;

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of("jakarta.validation.*");
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(STRICT_DEFINITIONS_OPTION);
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
        visitorContext = context;
        classElement = element;
        if (classElement.isInterface() && (classElement.hasAnnotation("jakarta.validation.GroupSequence")
            || classElement.isAssignable("jakarta.validation.groups.Default"))) {
            classElement.annotate(Introspected.class);
        }
        recordGroups(classElement.getAnnotationMetadata());
    }

    /**
     * Records the generic hierarchy of every group a constraint or a group conversion names, so that the validator
     * reads the groups a group extends without reading the class, introspected or not.
     */
    private void recordGroups(AnnotationMetadata metadata) {
        if (visitorContext == null || classElement == null) {
            return;
        }
        for (String constraint : metadata.getAnnotationNamesByStereotype(ANN_CONSTRAINT)) {
            registerAnnotationBuilder(constraint);
            for (var occurrence : metadata.getAnnotationValuesByName(constraint)) {
                for (var group : occurrence.annotationClassValues("groups")) {
                    recordGroup(group.getName());
                }
            }
        }
        for (var conversion : metadata.getAnnotationValuesByName("jakarta.validation.groups.ConvertGroup")) {
            conversion.annotationClassValue("from").ifPresent(group -> recordGroup(group.getName()));
            conversion.annotationClassValue("to").ifPresent(group -> recordGroup(group.getName()));
        }
    }

    /**
     * Registers the builder of a constraint and of the constraints it composes (micronaut-core#14055), so that a
     * Jakarta validator is initialized with, and a descriptor answers, an annotation instance without reflection.
     * The constraints this module registers itself, an annotation core cannot build from generated code because it
     * or a member type is not public, and one whose builder name would not fit a file name are left out.
     */
    private void registerAnnotationBuilder(String constraint) {
        VisitorContext context = visitorContext;
        ClassElement origin = classElement;
        if (context == null || origin == null || shippedBuilders(context).contains(constraint)
            || !registeredBuilders.add(origin.getName() + ":" + constraint)) {
            return;
        }
        context.getClassElement(constraint).filter(AnnotationElement.class::isInstance).ifPresent(type -> {
            // core names the builder after its owner and the whole annotation name, and writes a service entry
            // under that name: past the file name limit of the file system the compilation fails
            if (origin.getName().length() + constraint.length() + BUILDER_NAME_OVERHEAD <= MAX_FILE_NAME
                && buildable(type, new HashSet<>())) {
                context.registerAnnotationBuilder(type, origin);
            }
            for (String composing : type.getAnnotationNamesByStereotype(ANN_CONSTRAINT)) {
                if (!composing.equals(constraint)) {
                    registerAnnotationBuilder(composing);
                }
            }
        });
    }

    /**
     * The constraints whose builders this module generates itself, read from the {@code @RegisterAnnotations} its
     * {@code ConstraintAnnotationBuilders} declares.
     */
    private Set<String> shippedBuilders(VisitorContext context) {
        Set<String> shipped = shippedBuilders;
        if (shipped == null) {
            shipped = context.getClassElement("io.micronaut.validation.validator.metadata.ConstraintAnnotationBuilders")
                .map(type -> Set.of(type.getAnnotationMetadata().stringValues("io.micronaut.core.annotation.RegisterAnnotations")))
                .orElse(Set.of());
            shippedBuilders = shipped;
        }
        return shipped;
    }

    /**
     * Whether generated code can implement an annotation: it, the annotation and enum types of its members and
     * the types enclosing them are public.
     */
    private static boolean buildable(ClassElement type, Set<String> visited) {
        if (!visited.add(type.getName())) {
            return true;
        }
        for (ClassElement current = type; current != null; current = current.getEnclosingType().orElse(null)) {
            if (!current.isPublic()) {
                return false;
            }
        }
        if (type instanceof AnnotationElement annotation) {
            for (var member : annotation.getMembers()) {
                ClassElement memberType = member.getType().isArray() ? member.getType().fromArray() : member.getType();
                if ((memberType.isEnum() || memberType instanceof AnnotationElement) && !buildable(memberType, visited)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void recordGroup(String name) {
        VisitorContext context = visitorContext;
        ClassElement origin = classElement;
        if (context == null || origin == null || name.equals("jakarta.validation.groups.Default")
            || !importedTypes.add(origin.getPackageName() + ":" + name)) {
            return;
        }
        context.getClassElement(name).filter(ClassElement::isInterface)
            .ifPresent(group -> context.tryRecordTypeHierarchy(group, origin));
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
        if (classElement == null || element.isStatic()) {
            return;
        }
        if (!visited.add(element)) {
            return;
        }
        var overridden = element.getOverriddenMethods();
        // what the override declares itself, before the inherited annotations are copied in: only a method validation
        // concerns, here or in a parent, is checked against what it inherits
        if (!overridden.isEmpty() && (requiresValidation(element) || overridden.stream().anyMatch(ValidationVisitor::requiresValidation))) {
            var declared = Stream.of(element.getParameters()).map(parameter -> declaredTypeUse(parameter, new HashSet<>()))
                .toArray(AnnotationValue<?>[]::new);
            element.annotate(ValidationMetadataSupport.DECLARED_PARAMETERS, builder -> builder.member("arguments", declared));
        }
        overridden.forEach(parent -> inheritAnnotationsForMethod(element, parent));

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

    static boolean requiresValidation(MethodElement method) {
        if (method.hasStereotype(ANN_CONSTRAINT) || method.hasStereotype(ANN_VALID)
            || method.hasStereotype("jakarta.validation.groups.ConvertGroup")
            || method.hasAnnotation("jakarta.validation.groups.ConvertGroup$List")
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

    static boolean hasValidation(TypedElement element, Set<Object> visited) {
        AnnotationMetadata metadata = element instanceof ClassElement type ? type.getTypeAnnotationMetadata() : element.getAnnotationMetadata();
        if (metadata.hasStereotype(ANN_CONSTRAINT) || hasContainerConstraint(metadata) || metadata.hasStereotype(ANN_VALID)
            || metadata.hasStereotype("jakarta.validation.groups.ConvertGroup")
            || metadata.hasAnnotation("jakarta.validation.groups.ConvertGroup$List")) {
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
            recordGroups(annotationMetadata);
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
            .anyMatch(value -> value.contains(ValidationAnnotationUtil.CONSTRAINT_TYPE));
    }

    private boolean visitTypedElementValidationAndMarkForValidationIfNeeded(TypedElement e, boolean requireOnConstraint) {
        boolean requires = false;
        ClassElement genericType = e.getGenericType();
        for (ClassElement typeArgument : genericType.getTypeArguments().values()) {
            // Make sure `visitElementValidationAndMarkForValidationIfNeeded` is invoked on all type arguments to mark it of cascading
            boolean requiresForType = visitElementValidationAndMarkForValidationIfNeeded(typeArgument, requireOnConstraint);
            requires |= requiresForType;
        }
        if (requires) {
            importContainerHierarchy(genericType);
        }
        if (!genericType.equals(e)) {
            requires |= visitElementValidationAndMarkForValidationIfNeeded(genericType, requireOnConstraint);
        }
        return requires;
    }

    /**
     * Records the generic hierarchy of a container whose type arguments are validated, so that the validator maps the
     * type argument a value extractor extracts onto the container's own without reading the class, a container of the
     * JDK included. The standard collections pass their variables in order and need no record, an introspected
     * container has its hierarchy recorded with its introspection, and one no record can describe is left to the
     * reflection module.
     */
    private void importContainerHierarchy(ClassElement container) {
        String name = container.getName();
        if (visitorContext == null || classElement == null || container.isPrimitive() || container.isArray()
            || ValidationAnnotationUtil.POSITIONAL_JDK_CONTAINERS.contains(name)
            || container.hasStereotype(Introspected.class)
            || !importedTypes.add(classElement.getPackageName() + ":" + name)) {
            return;
        }
        visitorContext.tryRecordTypeHierarchy(container, classElement);
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
