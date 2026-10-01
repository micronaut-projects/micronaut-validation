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

import io.micronaut.annotation.processing.visitor.ElementProvider;
import io.micronaut.annotation.processing.visitor.JavaElementFactory;
import io.micronaut.annotation.processing.visitor.JavaVisitorContext;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.VisitorContext;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Java compiler access kept separate so Groovy and Kotlin processors do not load javac adapters.
 */
final class JavaAnnotationDeclarations {
    private JavaAnnotationDeclarations() { }

    static List<MethodElement> methods(ClassElement type, VisitorContext context) {
        if (type.getNativeType() instanceof ElementProvider nativeElement
                && nativeElement.element() instanceof TypeElement declaration
                && context.getElementFactory() instanceof JavaElementFactory factory) {
            return declaration.getEnclosedElements().stream()
                    .filter(element -> element.getKind() == ElementKind.METHOD)
                    .map(
                            element ->
                                    declaredMethod(
                                            type, (ExecutableElement) element, factory, context))
                    .toList();
        }
        return type.getMethods();
    }

    private static MethodElement declaredMethod(
            ClassElement type,
            ExecutableElement declaration,
            JavaElementFactory factory,
            VisitorContext context) {
        var method =
                factory.newMethodElement(
                        type, declaration, context.getElementAnnotationMetadataFactory());
        if (context instanceof JavaVisitorContext javaContext) {
            var builder = javaContext.getAnnotationMetadataBuilder();
            var parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                parameters[i] =
                        parameters[i].withAnnotationMetadata(
                                builder.buildDeclared(declaration.getParameters().get(i)));
            }
            return method.withParameters(parameters)
                    .withAnnotationMetadata(builder.buildDeclared(declaration));
        }
        return method;
    }

    static @Nullable String compositionError(ClassElement type) {
        // The flattened repeatable index cannot distinguish direct annotations from their
        // container.
        // Read the compiler's declaration before emitting the immutable runtime diagnostic.
        if (type.getNativeType() instanceof ElementProvider nativeElement
                && nativeElement.element() instanceof TypeElement declaration) {
            var direct =
                    declaration.getAnnotationMirrors().stream()
                            .map(annotation -> annotation.getAnnotationType().toString())
                            .collect(Collectors.toSet());
            for (var annotation : declaration.getAnnotationMirrors()) {
                for (var member : annotation.getElementValues().entrySet()) {
                    if (!member.getKey().getSimpleName().contentEquals("value")) {
                        continue;
                    }
                    if (member.getValue().getValue() instanceof List<?> values) {
                        for (Object value : values) {
                            if (value instanceof AnnotationValue av
                                    && av.getValue() instanceof AnnotationMirror nested
                                    && direct.contains(nested.getAnnotationType().toString())) {
                                return "A constraint composes "
                                        + nested.getAnnotationType()
                                        + " both directly and in a container: "
                                        + type.getName();
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
}
