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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.beans.visitor.IntrospectedTypeElementVisitor;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import io.micronaut.validation.validator.metadata.ContainerMapping;
import io.micronaut.validation.validator.metadata.ContainerMappings;
import io.micronaut.validation.validator.metadata.ValidationField;
import io.micronaut.validation.validator.metadata.ValidationMetadataSupport;
import io.micronaut.validation.validator.metadata.ValidationRecordAccessor;

import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
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
        if (!element.hasStereotype(Introspected.class)) {
            return;
        }
        recordHierarchy(element);
        recordContainerMappings(element);
        recordFields(element);
        registerAnnotatedFields(element, context);
        element.getMethods().stream()
            .filter(method -> !method.isStatic() && !method.isPrivate() && method.isAccessible())
            .filter(method -> ValidationVisitor.requiresValidation(method)
                || method.getOverriddenMethods().stream().anyMatch(ValidationVisitor::requiresValidation))
            .forEach(method -> method.annotate(Executable.class));
        if (element.isRecord()) {
            element.getBeanProperties().forEach(property -> property.getReadMethod()
                .filter(method -> method.getName().equals(property.getName()))
                .ifPresent(method -> method.annotate(ValidationRecordAccessor.class)));
        }
        configureIntrospection(element, context);
    }

    private static void recordHierarchy(ClassElement element) {
        var hierarchy = new LinkedHashMap<String, AnnotationValue<?>>();
        hierarchy(element, hierarchy);
        var arguments = element.getAllTypeArguments().entrySet().stream()
            .map(entry -> AnnotationValue.builder("io.micronaut.validation.internal.TypeArguments")
                .member("type", entry.getKey())
                .member("arguments", entry.getValue().values().stream()
                    .map(type -> typeUse(type, new HashSet<>())).toArray(AnnotationValue<?>[]::new)).build())
            .toArray(AnnotationValue<?>[]::new);
        element.annotate(ValidationMetadataSupport.HIERARCHY,
            builder -> builder.member("types", hierarchy.values().toArray(AnnotationValue<?>[]::new))
                .member("arguments", arguments)
                .member("methods", element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared()).stream()
                    .map(method -> AnnotationValue.builder("io.micronaut.validation.internal.Method")
                        .member("name", method.getName())
                        .member("unconstrained", !method.isStatic() && !method.isPrivate() && method.isAccessible()
                            && !ValidationVisitor.requiresValidation(method)
                            && method.getOverriddenMethods().stream().noneMatch(ValidationVisitor::requiresValidation))
                        .member("returnType", new AnnotationClassValue<>(method.getReturnType().getName()))
                        .member("parameters", Stream.of(method.getParameters())
                            .map(parameter -> new AnnotationClassValue<>(parameter.getType().getName()))
                            .toArray(AnnotationClassValue<?>[]::new)).build())
                    .toArray(AnnotationValue<?>[]::new)));
        element.getFields().forEach(field -> field.annotate(ValidationMetadataSupport.TYPE_USE,
            builder -> builder.members(typeUse(field.getGenericType(), new HashSet<>()).getValues())));
        element.getMethods().stream().filter(method -> method.getParameters().length == 0)
            .forEach(method -> method.annotate(ValidationMetadataSupport.TYPE_USE,
                builder -> builder.members(typeUse(method.getGenericReturnType(), new HashSet<>()).getValues())));
    }

    private static void recordContainerMappings(ClassElement element) {
        List<? extends GenericPlaceholderElement> own = element.getDeclaredGenericPlaceholders();
        AnnotationValue<?>[] mappings = element.getAllTypeArguments().entrySet().stream()
            .map(entry -> AnnotationValue.builder(ContainerMapping.class)
                .member("type", entry.getKey())
                .member("indexes", entry.getValue().values().stream()
                    .mapToInt(type -> variableIndex(type, own)).toArray())
                .build())
            .toArray(AnnotationValue<?>[]::new);
        element.annotate(ContainerMappings.class, builder -> builder.member("value", mappings));
    }

    private static void recordFields(ClassElement element) {
        ClassElement accessType = ClassElement.of(element.getName() + "$ValidationAccess");
        boolean propertyAccess = element.isRecord() || Stream.of(element.getAnnotationMetadata()
            .enumValues(Introspected.class, "accessKind", Introspected.AccessKind.class))
            .noneMatch(kind -> kind == Introspected.AccessKind.FIELD);
        Set<String> propertyFields = propertyAccess ? element.getBeanProperties().stream()
            .filter(property -> property.getReadMethod()
                .filter(method -> !method.isReflectionRequired(accessType)).isPresent())
            .flatMap(property -> property.getField().stream())
            .map(field -> field.getDeclaringType().getName() + "." + field.getName())
            .collect(Collectors.toSet()) : Set.of();
        element.getFields().forEach(field -> field.annotate(ValidationField.class, builder -> builder
            .member("property", propertyFields.contains(field.getDeclaringType().getName() + "." + field.getName()))
            .member("reflection", field.isReflectionRequired(accessType))));
    }

    private static void configureIntrospection(ClassElement element, VisitorContext context) {
        AnnotationValue<Introspected> introspected = element.getAnnotationMetadata().getAnnotation(Introspected.class);
        List<AnnotationValue<Introspected.IndexedAnnotation>> indexes = introspected == null
            ? List.of() : introspected.getAnnotations("indexed", Introspected.IndexedAnnotation.class);
        boolean constrainedConstructors = element.getEnclosedElements(ElementQuery.CONSTRUCTORS).stream()
            .anyMatch(ValidationVisitor::requiresValidation);
        element.removeAnnotation(Introspected.class);
        element.annotate(Introspected.class, builder -> {
            if (introspected != null) {
                builder.members(new LinkedHashMap<>(introspected.getValues()));
            }
            if (context.getLanguage() == VisitorContext.Language.KOTLIN) {
                // KSP exposes backing fields through native properties.
                builder.member("accessKind", new Introspected.AccessKind[]{
                    Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD
                });
            }
            builder.member("indexed", Stream.concat(indexes.stream(),
                Stream.of(INTROSPECTION_INDEXED_CONSTRAINT, INTROSPECTION_INDEXED_VALID))
                .toArray(AnnotationValue<?>[]::new));
            builder.member("members", true);
            if (constrainedConstructors) {
                builder.member("constructors", true);
            }
        });
    }

    private static void registerAnnotatedFields(ClassElement element, VisitorContext context) {
        var byType = new LinkedHashMap<String, List<String>>();
        ClassElement accessType = ClassElement.of(element.getName() + "$ValidationAccess");
        for (var field : element.getFields()) {
            if (field.getAnnotationMetadata().hasDeclaredAnnotation(ReflectiveAccess.class)
                && field.isReflectionRequired(accessType)) {
                byType.computeIfAbsent(field.getDeclaringType().getName(), ignored -> new ArrayList<>())
                    .add(field.getName());
            }
        }
        if (byType.isEmpty()) {
            return;
        }
        String json = byType.entrySet().stream()
            .map(entry -> "{\"name\":\"" + entry.getKey() + "\",\"fields\":["
                + entry.getValue().stream().distinct().map(name -> "{\"name\":\"" + name + "\"}")
                    .collect(Collectors.joining(",")) + "]}")
            .collect(Collectors.joining(",", "[", "]"));
        var resource = context.visitMetaInfFile(
            "native-image/io.micronaut.validation/" + element.getName() + "/reflect-config.json", element);
        try {
            if (resource.isPresent()) {
                resource.get().write(writer -> writer.write(json));
            }
        } catch (IOException e) {
            throw new ProcessingException(element, "Cannot register annotated validation fields", e);
        }
    }

    private static AnnotationValue<?> typeUse(ClassElement type, Set<Object> visited) {
        var value = AnnotationValue.builder(ValidationMetadataSupport.TYPE_USE);
        var annotations = type.getTypeAnnotationMetadata().getAnnotationNames().stream()
            .flatMap(name -> type.getTypeAnnotationMetadata().getAnnotationValuesByName(name).stream())
            .toArray(AnnotationValue<?>[]::new);
        value.member("annotations", annotations);
        if (!type.isPrimitive() && visited.add(type.getNativeType())) {
            value.member("arguments", type.getTypeArguments().values().stream()
                .map(argument -> typeUse(argument, new HashSet<>(visited)))
                .toArray(AnnotationValue<?>[]::new));
        }
        return value.build();
    }

    private static void hierarchy(ClassElement type, LinkedHashMap<String, AnnotationValue<?>> entries) {
        if (entries.containsKey(type.getName())) {
            return;
        }
        var value = AnnotationValue.builder("io.micronaut.validation.internal.Type")
            .member("type", new AnnotationClassValue<>(type.getName()))
            .member("interfaces", type.getInterfaces().stream()
                .map(it -> new AnnotationClassValue<>(it.getName())).toArray(AnnotationClassValue<?>[]::new));
        type.getSuperType().ifPresent(parent -> value.member("superType", new AnnotationClassValue<>(parent.getName())));
        entries.put(type.getName(), value.build());
        type.getSuperType().filter(parent -> !parent.getName().equals(Object.class.getName()))
            .ifPresent(parent -> hierarchy(parent, entries));
        type.getInterfaces().forEach(parent -> hierarchy(parent, entries));
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
