/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.validation.tck.runtime;

import io.micronaut.context.annotation.Executable;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Vetoed;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.validation.visitor.ValidationVisitor;
import jakarta.validation.executable.ExecutableType;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

@Internal
public final class TestClassVisitor implements TypeElementVisitor<Object, Object> {

    private static final String VALIDATE_ON_EXECUTION = "jakarta.validation.executable.ValidateOnExecution";
    private static final String GLOBAL_EXECUTABLE_PACKAGE = "org.hibernate.beanvalidation.tck.tests.integration.cdi.executable.global.";
    private static final String GLOBALLY_DISABLED_EXECUTABLE_PACKAGE = "org.hibernate.beanvalidation.tck.tests.integration.cdi.executable.globallydisabled.";

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public int getOrder() {
        return 88;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        process(element);
        if (element.hasAnnotation(VisitValidation.class)) {
            Arrays.stream(element.getAnnotationMetadata().stringValues(VisitValidation.class, "classNames"))
                .flatMap(cl -> context.getClassElement(cl).stream())
                .forEach(classElement -> {
                    ValidationVisitor validationVisitor = new ValidationVisitor();
                    validationVisitor.visitClass(classElement, context);
                    classElement.getDefaultConstructor().ifPresent(methodElement -> {
                        validationVisitor.visitConstructor((ConstructorElement) methodElement, context);
                    });
                    classElement.getFields().forEach(fieldElement -> validationVisitor.visitField(fieldElement, context));
                    classElement.getMethods().forEach(methodElement -> validationVisitor.visitMethod(methodElement, context));
                });
        }
    }

    private void process(ClassElement element) {
        if (element.getName().startsWith("org.hibernate.beanvalidation.tck.tests")) {
            if (element.isAssignable("jakarta.validation.ClockProvider")) {
                element.annotate(Vetoed.class);
                return;
            }
            if (element.isAssignable("jakarta.validation.valueextraction.ValueExtractor")) {
                element.annotate(Vetoed.class);
                return;
            }
            if (element.isAssignable("jakarta.validation.MessageInterpolator")) {
                element.annotate(Vetoed.class);
                return;
            }
            if (element.isAssignable("jakarta.validation.TraversableResolver")) {
                element.annotate(Vetoed.class);
                return;
            }
            if (element.isAssignable("jakarta.validation.ParameterNameProvider")) {
                element.annotate(Vetoed.class);
                return;
            }
            element.annotate(Introspected.class, builder -> {
                builder.member("accessKind", new Introspected.AccessKind[]{Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD});
                builder.member("visibility", Introspected.Visibility.ANY);
                // every declared constructor, the way the metadata API describes them
                builder.member("constructors", true);
            });
            if (!element.isRecord()) {
                element.annotate(Prototype.class);
            }

            element.getMethods().forEach(ce -> {
                if (ce.isStatic() || !ce.isAccessible()) {
                    ce.annotate(Vetoed.class);
                } else if (isValidatedExecutable(element, ce)) {
                    ce.annotate(Executable.class);
                } else {
                    ce.annotate(Vetoed.class);
                }
            });
            element.getFields().forEach(this::processField);
        }
    }

    @Override
    public void visitField(FieldElement element, VisitorContext context) {
        processField(element);
    }

    private void processField(FieldElement field) {
        if (field.getDeclaringType().getName().startsWith("org.hibernate.beanvalidation.tck.tests")
            && (field.hasAnnotation("jakarta.ejb.EJB")
                || field.hasAnnotation("jakarta.annotation.Resource")
                || field.getType().isAssignable(jakarta.validation.Validator.class)
                || field.getType().isAssignable(jakarta.validation.ValidatorFactory.class))) {
            field.annotate("jakarta.inject.Inject");
        }
    }

    private static boolean isValidatedExecutable(ClassElement beanType, MethodElement method) {
        boolean getter = isGetter(method);
        if (beanType.getName().startsWith(GLOBALLY_DISABLED_EXECUTABLE_PACKAGE)) {
            return false;
        }
        if (beanType.getName().startsWith(GLOBAL_EXECUTABLE_PACKAGE)) {
            return getter;
        }
        Optional<ExecutableType[]> methodTypes = executableTypes(method);
        if (methodTypes.isPresent()) {
            return includes(methodTypes.get(), getter);
        }
        Optional<ExecutableType[]> inheritedTypes = inheritedExecutableTypes(method);
        if (inheritedTypes.isPresent()) {
            return includes(inheritedTypes.get(), getter);
        }
        Optional<ExecutableType[]> declaringTypeTypes = executableTypes(method.getDeclaringType());
        return declaringTypeTypes.map(executableTypes -> includes(executableTypes, getter)).orElse(!getter);
    }

    private static Optional<ExecutableType[]> inheritedExecutableTypes(MethodElement method) {
        return inheritedExecutableTypes(method, method.getDeclaringType().getInterfaces())
            .or(() -> method.getDeclaringType().getSuperType()
                .flatMap(superType -> inheritedExecutableTypes(method, superType)));
    }

    private static Optional<ExecutableType[]> inheritedExecutableTypes(MethodElement method, Collection<ClassElement> types) {
        for (ClassElement type : types) {
            Optional<ExecutableType[]> executableTypes = inheritedExecutableTypes(method, type);
            if (executableTypes.isPresent()) {
                return executableTypes;
            }
        }
        return Optional.empty();
    }

    private static Optional<ExecutableType[]> inheritedExecutableTypes(MethodElement method, ClassElement type) {
        Optional<MethodElement> overriddenMethod = type.findMethod(method.getName())
            .filter(candidate -> candidate.getParameters().length == method.getParameters().length);
        if (overriddenMethod.isPresent()) {
            Optional<ExecutableType[]> methodTypes = executableTypes(overriddenMethod.get());
            if (methodTypes.isPresent()) {
                return methodTypes;
            }
            Optional<ExecutableType[]> typeTypes = executableTypes(type);
            if (typeTypes.isPresent()) {
                return typeTypes;
            }
        }
        return inheritedExecutableTypes(method, type.getInterfaces())
            .or(() -> type.getSuperType().flatMap(superType -> inheritedExecutableTypes(method, superType)));
    }

    private static Optional<ExecutableType[]> executableTypes(MethodElement method) {
        if (!method.getMethodAnnotationMetadata().hasAnnotation(VALIDATE_ON_EXECUTION)) {
            return Optional.empty();
        }
        ExecutableType[] executableTypes = method.getMethodAnnotationMetadata()
            .findAnnotation(VALIDATE_ON_EXECUTION)
            .filter(annotationValue -> annotationValue.contains("type"))
            .map(annotationValue -> annotationValue.enumValues("type", ExecutableType.class))
            .orElse(new ExecutableType[] {ExecutableType.IMPLICIT});
        return Optional.of(executableTypes);
    }

    private static Optional<ExecutableType[]> executableTypes(ClassElement type) {
        if (!type.hasAnnotation(VALIDATE_ON_EXECUTION)) {
            return Optional.empty();
        }
        ExecutableType[] executableTypes = type.getAnnotationMetadata()
            .findAnnotation(VALIDATE_ON_EXECUTION)
            .filter(annotationValue -> annotationValue.contains("type"))
            .map(annotationValue -> annotationValue.enumValues("type", ExecutableType.class))
            .orElse(new ExecutableType[] {ExecutableType.IMPLICIT});
        if (executableTypes.length == 1 && executableTypes[0] == ExecutableType.IMPLICIT) {
            return Optional.empty();
        }
        return Optional.of(executableTypes);
    }

    private static boolean includes(ExecutableType[] executableTypes, boolean getter) {
        if (executableTypes.length == 0) {
            return false;
        }
        for (ExecutableType executableType : executableTypes) {
            if (executableType == ExecutableType.ALL
                || executableType == ExecutableType.IMPLICIT
                || (getter && executableType == ExecutableType.GETTER_METHODS)
                || (!getter && executableType == ExecutableType.NON_GETTER_METHODS)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGetter(MethodElement method) {
        return method.getParameters().length == 0
            && method.getReturnType() != null
            && !method.getReturnType().isAssignable(Void.TYPE)
            && (method.getName().startsWith("get") || method.getName().startsWith("is"));
    }
}
