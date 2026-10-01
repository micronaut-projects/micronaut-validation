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

import io.micronaut.context.annotation.Executable;


import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.beans.visitor.IntrospectedTypeElementVisitor;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import io.micronaut.validation.validator.metadata.ContainerMapping;
import io.micronaut.validation.validator.metadata.ContainerMappings;
import io.micronaut.validation.validator.metadata.ValidationField;
import io.micronaut.validation.validator.metadata.ValidationRecordAccessor;

import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The visitor add property indexes for the validated annotations.
 *
 * @author Denis Stepanov
 * @since 4.0.0
 */
@Internal
public class IntrospectedValidationIndexesVisitor implements TypeElementVisitor<Object, Object> {

    private static final String ANN_CONSTRAINT = "jakarta.validation.Constraint";
    private static final String ANN_VALID = "jakarta.validation.Valid";

    private static final AnnotationValue<Introspected.IndexedAnnotation> INTROSPECTION_INDEXED_CONSTRAINT = AnnotationValue.builder(Introspected.IndexedAnnotation.class)
        .member("annotation", new AnnotationClassValue<>(ANN_CONSTRAINT))
        .build();
    private static final AnnotationValue<Introspected.IndexedAnnotation> INTROSPECTION_INDEXED_VALID = AnnotationValue.builder(Introspected.IndexedAnnotation.class)
        .member("annotation", new AnnotationClassValue<>(ANN_VALID))
        .build();

    @Override
    public int getOrder() {
        return IntrospectedTypeElementVisitor.POSITION + 10; // Should just before the introspected visitor
    }

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of("jakarta.validation.*", "io.micronaut.core.annotation.Introspected");
    }

    @NonNull
    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element.hasStereotype(Introspected.class)) {
            List<? extends GenericPlaceholderElement> own =
                    element.getDeclaredGenericPlaceholders();
            AnnotationValue<?>[] mappings =
                    element.getAllTypeArguments().entrySet().stream()
                            .map(
                                    entry ->
                                            AnnotationValue.builder(ContainerMapping.class)
                                                    .member("type", entry.getKey())
                                                    .member(
                                                            "indexes",
                                                            entry.getValue().values().stream()
                                                                    .mapToInt(
                                                                            type ->
                                                                                    variableIndex(
                                                                                            type,
                                                                                            own))
                                                                    .toArray())
                                                    .build())
                            .toArray(AnnotationValue<?>[]::new);
            element.annotate(ContainerMappings.class, builder -> builder.member("value", mappings));
            element.getFields()
                    .forEach(
                            field ->
                                    field.annotate(
                                            ValidationField.class,
                                            builder ->
                                                    builder.member(
                                                                    "reflection",
                                                                    field.isReflectionRequired(
                                                                            ClassElement.of(
                                                                                    element
                                                                                                    .getName()
                                                                                            + "$ValidationAccess")))
                                                            .member(
                                                                    "authorized",
                                                                    field.getAnnotationMetadata()
                                                                            .hasDeclaredAnnotation(
                                                                                    ReflectiveAccess
                                                                                            .class))));
            registerAnnotatedFields(element, context);
            element.getMethods().stream()
                    .filter(
                            method ->
                                    !method.isStatic()
                                            && !method.isPrivate()
                                            && method.isAccessible()
                                            && !method.getDeclaringType()
                                                    .getName()
                                                    .equals(Object.class.getName()))
                    .forEach(method -> method.annotate(Executable.class));
            if (element.isRecord()) {
                element.getBeanProperties()
                        .forEach(
                                property ->
                                        property.getReadMethod()
                                                .filter(
                                                        method ->
                                                                method.getName()
                                                                        .equals(property.getName()))
                                                .ifPresent(
                                                        method ->
                                                                method.annotate(
                                                                        ValidationRecordAccessor
                                                                                .class)));
            }
            AnnotationMetadata annotationMetadata = element.getAnnotationMetadata();
            AnnotationValue<Introspected> introspectedAnnotation = annotationMetadata.getAnnotation(Introspected.class);
            List<AnnotationValue<Introspected.IndexedAnnotation>> declaredIndexed = introspectedAnnotation == null
                ? List.of()
                : introspectedAnnotation.getAnnotations("indexed", Introspected.IndexedAnnotation.class);
            element.removeAnnotation(Introspected.class);
            element.annotate(
                    Introspected.class,
                    builder -> {
                        if (introspectedAnnotation != null) {
                            builder.members(
                                    new LinkedHashMap<>(introspectedAnnotation.getValues()));
                        }
                        if (context.getLanguage() == VisitorContext.Language.KOTLIN) {
                            // KSP exposes backing fields through native properties. Members still
                            // read each field itself.
                            builder.member(
                                    "accessKind",
                                    new Introspected.AccessKind[] {
                                        Introspected.AccessKind.FIELD,
                                        Introspected.AccessKind.METHOD
                                    });
                        }
                        AnnotationValue<?>[] indexed = Stream.concat(
                    declaredIndexed.stream(),
                    Stream.of(INTROSPECTION_INDEXED_CONSTRAINT, INTROSPECTION_INDEXED_VALID)
                ).toArray(AnnotationValue<?>[]::new);
                builder.member("indexed", indexed);
                        builder.member("members", true);
                        builder.member("constructors", true);
                    });
        }
    }

    private static void registerAnnotatedFields(ClassElement element, VisitorContext context) {
        var byType = new LinkedHashMap<String, java.util.List<String>>();
        for (var field : element.getFields()) {
            if (field.getAnnotationMetadata().hasDeclaredAnnotation(ReflectiveAccess.class)
                    && field.isReflectionRequired(
                            ClassElement.of(element.getName() + "$ValidationAccess"))) {
                byType.computeIfAbsent(
                                field.getDeclaringType().getName(), ignored -> new ArrayList<>())
                        .add(field.getName());
            }
        }
        if (byType.isEmpty()) {
            return;
        }
        String json =
                byType.entrySet().stream()
                        .map(
                                entry ->
                                        "{\"name\":\""
                                                + entry.getKey()
                                                + "\",\"fields\":["
                                                + entry.getValue().stream()
                                                        .distinct()
                                                        .map(name -> "{\"name\":\"" + name + "\"}")
                                                        .collect(Collectors.joining(","))
                                                + "]}")
                        .collect(Collectors.joining(",", "[", "]"));
        var resource =
                context.visitMetaInfFile(
                        "native-image/io.micronaut.validation/"
                                + element.getName()
                                + "/reflect-config.json",
                        element);
        try {
            if (resource.isPresent()) {
                resource.get().write(writer -> writer.write(json));
            }
        } catch (IOException e) {
            throw new ProcessingException(
                    element, "Cannot register annotated validation fields", e);
        }
    }

    private static int variableIndex(
            ClassElement type, List<? extends GenericPlaceholderElement> own) {
        if (type instanceof GenericPlaceholderElement placeholder) {
            ClassElement resolved = placeholder.getResolved().orElse(null);
            if (resolved != null && resolved != type) {
                return variableIndex(resolved, own);
            }
            for (int i = 0; i < own.size(); i++) {
                GenericPlaceholderElement variable = own.get(i);
                if (variable.getVariableName().equals(placeholder.getVariableName())
                        && variable.getDeclaringElement()
                                .equals(placeholder.getDeclaringElement())) {
                    return i;
                }
            }
        }
        return -1;
    }
}
