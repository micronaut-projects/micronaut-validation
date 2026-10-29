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
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.beans.visitor.IntrospectedTypeElementVisitor;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.utils.JsonWriter;
import io.micronaut.inject.validation.RequiresValidation;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

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

    /**
     * The annotation processor option describing every introspected type for validation, the ones without a
     * constraint included: what an XML constraint mapping of a type declaring none needs.
     */
    public static final String DESCRIBE_ALL_OPTION = "micronaut.validation.describeAllIntrospections";

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

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(DESCRIBE_ALL_OPTION);
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
        if (!participates(element) && !Boolean.parseBoolean(context.getOptions().get(DESCRIBE_ALL_OPTION))) {
            // an introspection of a type that takes no part in validation - a serialization DTO, an entity
            // without constraints - is left as its author declared it: other modules read it too. A type
            // constrained only by an XML mapping asks for the option
            return;
        }
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

    /**
     * Whether a type takes part in validation: it or a super type declares a constraint, a cascade or a
     * group sequence on the type or on a field, method or constructor, or it is an interface, which a group
     * or a constrained contract can be, or a validator or value extractor.
     */
    static boolean participates(ClassElement element) {
        if (element.isInterface()
            || element.isAssignable("jakarta.validation.ConstraintValidator")
            || element.isAssignable("jakarta.validation.valueextraction.ValueExtractor")) {
            return true;
        }
        return declaresValidation(element, new HashSet<>());
    }

    private static boolean declaresValidation(ClassElement type, Set<String> visited) {
        if (type.getName().equals(Object.class.getName()) || !visited.add(type.getName())) {
            return false;
        }
        if (type.hasStereotype(ANN_CONSTRAINT) || type.hasStereotype(ANN_VALID)
            || type.hasAnnotation("jakarta.validation.GroupSequence")
            || type.hasAnnotation(RequiresValidation.class)) {
            return true;
        }
        for (FieldElement field : type.getEnclosedElements(ElementQuery.ALL_FIELDS.onlyDeclared())) {
            if (ValidationVisitor.hasValidation(field, new HashSet<>())) {
                return true;
            }
        }
        for (MethodElement method : type.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())) {
            if (ValidationVisitor.requiresValidation(method)) {
                return true;
            }
        }
        for (ConstructorElement constructor : type.getEnclosedElements(ElementQuery.CONSTRUCTORS)) {
            if (ValidationVisitor.requiresValidation(constructor)) {
                return true;
            }
        }
        if (type.getSuperType().filter(parent -> declaresValidation(parent, visited)).isPresent()) {
            return true;
        }
        for (ClassElement anInterface : type.getInterfaces()) {
            if (declaresValidation(anInterface, visited)) {
                return true;
            }
        }
        return false;
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
            // the super types and the declaring levels of the methods, which Jakarta Validation applies apart
            builder.member("hierarchy", true);
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
        var document = new JsonWriter().beginArray();
        byType.forEach((type, fields) -> {
            document.beginObject().name("name").value(type).name("fields").beginArray();
            fields.stream().distinct().forEach(name -> document.beginObject().name("name").value(name).endObject());
            document.endArray().endObject();
        });
        String json = document.endArray().toString();
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
}
