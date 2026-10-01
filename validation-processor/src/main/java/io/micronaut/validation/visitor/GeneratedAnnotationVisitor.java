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

import io.micronaut.annotation.processing.visitor.ElementProvider;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.annotation.AnnotationMetadataGenUtils;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.ast.AnnotationElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.EnumElement;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.bytecode.ByteCodeGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.validation.validator.metadata.AnnotationMember;
import io.micronaut.validation.validator.metadata.AnnotationMemberValues;
import io.micronaut.validation.validator.metadata.GeneratedAnnotationFactories;
import io.micronaut.validation.validator.metadata.GeneratedAnnotationProvider;
import io.micronaut.validation.validator.metadata.GeneratedConstraintDefinition;
import io.micronaut.validation.validator.metadata.ValidationMetadata;
import io.micronaut.validation.validator.metadata.ValidationTypeMetadata;

import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import javax.lang.model.element.Modifier;

/**
 * Emits concrete annotation implementations from Sourcegen models. JVM output also supports Kotlin
 * annotation interfaces, which Kotlin source cannot implement.
 *
 * @since 5.3.0
 */
@Internal
public final class GeneratedAnnotationVisitor implements TypeElementVisitor<Object, Object> {

    private final Map<ClassElement, Map<String, ClassElement>> annotations = new LinkedHashMap<>();
    private final Map<ClassElement, Map<String, ClassElement>> types = new LinkedHashMap<>();
    private final Map<ClassElement, Map<String, ClassElement>> enums = new LinkedHashMap<>();
    private @Nullable ClassElement origin;

    /** Creates the visitor used by annotation processing. */
    public GeneratedAnnotationVisitor() { }

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of("*");
    }

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element.getName().contains("$ValidationAnnotations")
                || element.getSimpleName().startsWith("$")
                || element.hasAnnotation("javax.annotation.processing.Generated")
                || element.isAssignable("io.micronaut.module.info.AbstractMicronautModuleInfo")) {
            origin = null;
            return;
        }
        element.annotate(
                ValidationMetadata.class,
                builder ->
                        builder.member(
                                "provider",
                                new AnnotationClassValue<>(
                                        providerName(element), true)));
        origin = element;
        Map<String, ClassElement> known =
                types.computeIfAbsent(element, ignored -> new LinkedHashMap<>());
        collectHierarchy(element, known);
        if (element instanceof EnumElement) {
            enums.computeIfAbsent(element, ignored -> new LinkedHashMap<>())
                    .put(element.getName(), element);
        }
        collect(element.getAnnotationMetadata(), context);
        element.getFields().forEach(field -> visitField(field, context));
        List<MethodElement> declarationMethods = context.getLanguage() == VisitorContext.Language.JAVA
            ? JavaAnnotationDeclarations.methods(element, context) : element.getMethods();
        declarationMethods.forEach(method -> visitMethod(method, context));
        if (element instanceof AnnotationElement) {
            annotations.computeIfAbsent(element, ignored -> new LinkedHashMap<>()).put(element.getName(), element);
        }
        element.getEnclosedElements(io.micronaut.inject.ast.ElementQuery.CONSTRUCTORS)
                .forEach(constructor -> visitMethod(constructor, context));
        generate(
                element,
                annotations.computeIfAbsent(element, ignored -> new LinkedHashMap<>()),
                context);
        origin = null;
    }

    @Override
    public void visitField(FieldElement element, VisitorContext context) {
        collect(element.getAnnotationMetadata(), context);
        collectType(element.getGenericType(), context, new java.util.HashSet<>());
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        collect(element.getAnnotationMetadata(), context);
        collectType(element.getGenericReturnType(), context, new java.util.HashSet<>());
        for (ParameterElement parameter : element.getParameters()) {
            collect(parameter.getAnnotationMetadata(), context);
            collectType(parameter.getGenericType(), context, new java.util.HashSet<>());
        }
    }

    private void collectType(ClassElement type, VisitorContext context, Set<Object> visited) {
        if (type.isPrimitive() || !visited.add(type.getNativeType())) {
            return;
        }
        collect(type.getTypeAnnotationMetadata(), context);
        type.getTypeArguments()
                .values()
                .forEach(argument -> collectType(argument, context, visited));
    }

    private void collect(AnnotationMetadata metadata, VisitorContext context) {
        if (origin == null) {
            return;
        }
        Map<String, ClassElement> found =
                annotations.computeIfAbsent(origin, ignored -> new LinkedHashMap<>());
        for (String name : metadata.getAnnotationNames()) {
            for (AnnotationValue<?> value : metadata.getAnnotationValuesByName(name)) {
                collectNested(value, found, context);
            }
        }
        for (String name :
                metadata.getAnnotationNamesByStereotype("jakarta.validation.Constraint")) {
            addAnnotation(name, found, context);
            for (AnnotationValue<?> value : metadata.getAnnotationValuesByName(name)) {
                if (name.equals("io.micronaut.validation.annotation.InEnum")
                        || name.equals("io.micronaut.validation.annotation.NotInEnum")) {
                    for (var enumClass : value.annotationClassValues("value")) {
                        context.getClassElement(enumClass.getName())
                                .ifPresent(
                                        type ->
                                                enums.computeIfAbsent(
                                                                Objects.requireNonNull(origin),
                                                                ignored -> new LinkedHashMap<>())
                                                        .put(type.getName(), type));
                    }
                }
                for (AnnotationClassValue<?> group : value.annotationClassValues("groups")) {
                    context.getClassElement(group.getName())
                            .ifPresent(
                                    type ->
                                            collectHierarchy(
                                                    type,
                                                    types.computeIfAbsent(
                                                            Objects.requireNonNull(origin),
                                                            ignored -> new LinkedHashMap<>())));
                }
            }
        }
    }

    private void collectNested(
            AnnotationValue<?> value, Map<String, ClassElement> found, VisitorContext context) {
        var type = context.getClassElement(value.getAnnotationName()).orElse(null);
        if (type != null && type.hasAnnotation("jakarta.validation.Constraint")) {
            addAnnotation(type.getName(), found, context);
            for (AnnotationClassValue<?> group : value.annotationClassValues("groups")) {
                context.getClassElement(group.getName())
                        .ifPresent(
                                groupType ->
                                        collectHierarchy(
                                                groupType,
                                                types.computeIfAbsent(
                                                        Objects.requireNonNull(origin),
                                                        ignored -> new LinkedHashMap<>())));
            }
        }
        for (Object attribute : value.getValues().values()) {
            if (attribute instanceof AnnotationValue<?> nested) {
                collectNested(nested, found, context);
            } else if (attribute instanceof AnnotationValue<?>[] nested) {
                for (AnnotationValue<?> item : nested) {
                    collectNested(item, found, context);
                }
            }
        }
    }

    private void addAnnotation(
            String name, Map<String, ClassElement> found, VisitorContext context) {
        if (found.containsKey(name)) {
            return;
        }
        ClassElement type = context.getClassElement(name).orElse(null);
        if (type == null || !(type instanceof AnnotationElement)) {
            return;
        }
        found.put(name, type);
        for (String composing :
                type.getAnnotationNamesByStereotype("jakarta.validation.Constraint")) {
            addAnnotation(composing, found, context);
        }
        for (MethodElement member : AnnotationMembers.of(type, context)) {
            ClassElement returned = member.getReturnType();
            if (returned.isArray()) {
                returned = returned.fromArray();
            }
            if (returned instanceof AnnotationElement) {
                addAnnotation(returned.getName(), found, context);
            }
        }
    }

    @Override
    public void finish(VisitorContext context) {
        annotations.clear();
        types.clear();
        enums.clear();
        origin = null;
    }

    private void collectHierarchy(ClassElement type, Map<String, ClassElement> known) {
        if (known.putIfAbsent(type.getName(), type) != null || type.isPrimitive()) {
            return;
        }
        type.getSuperType().ifPresent(parent -> collectHierarchy(parent, known));
        type.getInterfaces().forEach(parent -> collectHierarchy(parent, known));
    }

    private static String providerName(ClassElement element) {
        // A type may be visited as source and later imported. Its provider identity must remain
        // stable when another visitor changes the import destination on the shared AST element.
        return element.getName().startsWith("jakarta.validation.constraints.")
            ? "io.micronaut.validation.metadata.generated." + element.getSimpleName() + "$ValidationAnnotations"
            : element.getName() + "$ValidationAnnotations";
    }

    private void generate(
            ClassElement element, Map<String, ClassElement> found, VisitorContext context) {
        String name = providerName(element);
        ClassDef.ClassDefBuilder provider =
                ClassDef.builder(name)
                        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                        .addAnnotation(AnnotationDef.builder(Internal.class).build())
                        .addSuperinterface(TypeDef.of(GeneratedAnnotationProvider.class));
        provider.addMethod(MethodDef.builder("ownerType").addModifiers(Modifier.PUBLIC).returns(Class.class)
            .build((self, parameters) -> ExpressionDef.constant(TypeDef.erasure(element)).returning()));
        Map<String, MethodDef> argumentLoadMethods = new LinkedHashMap<>();
        var loadArgumentClass =
                AnnotationMetadataGenUtils.createLoadClassValueExpressionFn(
                        ClassTypeDef.of(name), argumentLoadMethods);
        provider.addMethod(GeneratedExecutableDeclarations.method(element,
            context.getLanguage() == VisitorContext.Language.JAVA ? JavaAnnotationDeclarations.methods(element, context) : element.getMethods(),
            ClassTypeDef.of(name), loadArgumentClass));
        provider.addMethod(
                MethodDef.builder("propertyArgument")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(Argument.class)
                        .addParameter("declaration", String.class)
                        .addParameter("argument", Argument.class)
                        .build(
                                (aThis, parameters) -> {
                                    List<StatementDef> body = new ArrayList<>();
                                    for (FieldElement field : element.getFields()) {
                                        String key =
                                                field.getDeclaringType().getName()
                                                        + ":FIELD:"
                                                        + field.getName();
                                        var argument =
                                                supplementedTypeUse(
                                                        field.getGenericType(),
                                                        parameters.get(1),
                                                        loadArgumentClass,
                                                        new java.util.HashSet<>());
                                        body.add(
                                                parameters
                                                        .getFirst()
                                                        .invoke(
                                                                "equals",
                                                                List.of(TypeDef.OBJECT),
                                                                TypeDef.Primitive.BOOLEAN,
                                                                List.of(
                                                                        ExpressionDef.constant(
                                                                                key)))
                                                        .ifTrue(argument.returning()));
                                    }
                                    for (MethodElement method : element.getMethods()) {
                                        if (method.getParameters().length != 0
                                                || method.isStatic()) {
                                            continue;
                                        }
                                        String key =
                                                method.getDeclaringType().getName()
                                                        + ":METHOD:"
                                                        + method.getName();
                                        var argument =
                                                supplementedTypeUse(
                                                        method.getGenericReturnType(),
                                                        parameters.get(1),
                                                        loadArgumentClass,
                                                        new java.util.HashSet<>());
                                        body.add(
                                                parameters
                                                        .getFirst()
                                                        .invoke(
                                                                "equals",
                                                                List.of(TypeDef.OBJECT),
                                                                TypeDef.Primitive.BOOLEAN,
                                                                List.of(
                                                                        ExpressionDef.constant(
                                                                                key)))
                                                        .ifTrue(argument.returning()));
                                    }
                                    element.getAllTypeArguments()
                                            .forEach(
                                                    (superType, arguments) -> {
                                                        int index = 0;
                                                        for (ClassElement type :
                                                                arguments.values()) {
                                                            String key =
                                                                    "SUPER:" + superType + ":"
                                                                            + index++;
                                                            var argument =
                                                                    supplementedTypeUse(
                                                                            type,
                                                                            parameters.get(1),
                                                                            loadArgumentClass,
                                                                            new java.util
                                                                                    .HashSet<>());
                                                            body.add(
                                                                    parameters
                                                                            .getFirst()
                                                                            .invoke(
                                                                                    "equals",
                                                                                    List.of(
                                                                                            TypeDef
                                                                                                    .OBJECT),
                                                                                    TypeDef
                                                                                            .Primitive
                                                                                            .BOOLEAN,
                                                                                    List.of(
                                                                                            ExpressionDef
                                                                                                    .constant(
                                                                                                            key)))
                                                                            .ifTrue(
                                                                                    argument
                                                                                            .returning()));
                                                        }
                                                    });
                                    body.add(parameters.get(1).returning());
                                    return StatementDef.multi(body);
                                }));
        Map<String, ClassElement> known = Objects.requireNonNull(types.get(element));
        provider.addMethod(
                MethodDef.builder("typeMetadata")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(ValidationTypeMetadata.class)
                        .addParameter("name", String.class)
                        .build(
                                (aThis, parameters) -> {
                                    List<StatementDef> body = new ArrayList<>();
                                    known.forEach(
                                            (typeName, type) -> {
                                                ExpressionDef parent =
                                                        type.getSuperType()
                                                                .map(
                                                                        superType ->
                                                                                (ExpressionDef)
                                                                                        ExpressionDef
                                                                                                .constant(
                                                                                                        TypeDef
                                                                                                                .erasure(
                                                                                                                        superType)))
                                                                .orElse(ExpressionDef.nullValue());
                                                List<ExpressionDef> interfaces =
                                                        type.getInterfaces().stream()
                                                                .map(
                                                                        inter ->
                                                                                (ExpressionDef)
                                                                                        ExpressionDef
                                                                                                .constant(
                                                                                                        TypeDef
                                                                                                                .erasure(
                                                                                                                        inter)))
                                                                .toList();
                                                ExpressionDef interfaceList =
                                                        ClassTypeDef.of(List.class)
                                                                .invokeStatic(
                                                                        "of",
                                                                        List.of(
                                                                                TypeDef.OBJECT
                                                                                        .array()),
                                                                        TypeDef.of(List.class),
                                                                        List.of(
                                                                                TypeDef.OBJECT
                                                                                        .array()
                                                                                        .instantiate(
                                                                                                interfaces)));
                                                ExpressionDef sequence = ExpressionDef.nullValue();
                                                AnnotationValue<?> groupSequence =
                                                        type.getAnnotation(
                                                                "jakarta.validation.GroupSequence");
                                                if (groupSequence != null) {
                                                    List<ExpressionDef> groups = new ArrayList<>();
                                                    for (AnnotationClassValue<?> group :
                                                            groupSequence.annotationClassValues(
                                                                    "value")) {
                                                        ClassElement groupType =
                                                                context.getClassElement(
                                                                                group.getName())
                                                                        .orElseThrow();
                                                        groups.add(
                                                                ExpressionDef.constant(
                                                                        TypeDef.erasure(
                                                                                groupType)));
                                                    }
                                                    sequence =
                                                            ClassTypeDef.of(List.class)
                                                                    .invokeStatic(
                                                                            "of",
                                                                            List.of(
                                                                                    TypeDef.OBJECT
                                                                                            .array()),
                                                                            TypeDef.of(List.class),
                                                                            List.of(
                                                                                    TypeDef.OBJECT
                                                                                            .array()
                                                                                            .instantiate(
                                                                                                    groups)));
                                                }
                                                body.add(
                                                        parameters
                                                                .getFirst()
                                                                .invoke(
                                                                        "equals",
                                                                        List.of(TypeDef.OBJECT),
                                                                        TypeDef.Primitive.BOOLEAN,
                                                                        List.of(
                                                                                ExpressionDef
                                                                                        .constant(
                                                                                                typeName)))
                                                                .ifTrue(
                                                                        ClassTypeDef.of(
                                                                                        ValidationTypeMetadata
                                                                                                .class)
                                                                                .instantiate(
                                                                                        List.of(
                                                                                                TypeDef
                                                                                                        .CLASS,
                                                                                                TypeDef
                                                                                                        .of(
                                                                                                                List
                                                                                                                        .class),
                                                                                                TypeDef
                                                                                                        .of(
                                                                                                                List
                                                                                                                        .class)),
                                                                                        List.of(
                                                                                                parent,
                                                                                                interfaceList,
                                                                                                sequence))
                                                                                .returning()));
                                            });
                                    body.add(ExpressionDef.nullValue().returning());
                                    return StatementDef.multi(body);
                                }));
        Map<String, ClassElement> references = new LinkedHashMap<>(known);
        references.putAll(found);
        references.putAll(enums.getOrDefault(element, Map.of()));
        for (ClassElement annotation : found.values()) {
            for (MethodElement member : AnnotationMembers.of(annotation, context)) {
                ClassElement returned = member.getReturnType();
                if (returned.isArray()) {
                    returned = returned.fromArray();
                }
                if (!returned.isPrimitive()) {
                    references.put(returned.getName(), returned);
                }
            }
            AnnotationValue<?> constraint =
                    annotation.getAnnotation("jakarta.validation.Constraint");
            if (constraint != null) {
                for (var validator : constraint.annotationClassValues("validatedBy")) {
                    context.getClassElement(validator.getName())
                            .ifPresent(type -> references.put(type.getName(), type));
                }
            }
        }
        provider.addMethod(
                MethodDef.builder("type")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(Class.class)
                        .addParameter("name", String.class)
                        .build(
                                (aThis, parameters) -> {
                                    List<StatementDef> body = new ArrayList<>();
                                    references.forEach(
                                            (typeName, type) ->
                                                    body.add(
                                                            parameters
                                                                    .getFirst()
                                                                    .invoke(
                                                                            "equals",
                                                                            List.of(TypeDef.OBJECT),
                                                                            TypeDef.Primitive
                                                                                    .BOOLEAN,
                                                                            List.of(
                                                                                    ExpressionDef
                                                                                            .constant(
                                                                                                    typeName)))
                                                                    .ifTrue(
                                                                            ExpressionDef.constant(
                                                                                            TypeDef
                                                                                                    .erasure(
                                                                                                            type))
                                                                                    .returning())));
                                    body.add(ExpressionDef.nullValue().returning());
                                    return StatementDef.multi(body);
                                }));
        provider.addMethod(
                MethodDef.builder("annotationMembers")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(Map.class)
                        .addParameter("name", String.class)
                        .build(
                                (aThis, parameters) -> {
                                    List<StatementDef> body = new ArrayList<>();
                                    found.forEach(
                                            (annotationName, annotation) -> {
                                                List<ExpressionDef> entries = new ArrayList<>();
                                                AnnotationMembers.of(annotation, context).stream()
                                                        .filter(
                                                                member ->
                                                                        !member.isStatic()
                                                                                && member
                                                                                                .getParameters()
                                                                                                .length
                                                                                        == 0
                                                                                && member.getDeclaringType()
                                                                                        .getName()
                                                                                        .equals(
                                                                                                annotationName))
                                                        .forEach(
                                                                member -> {
                                                                    boolean required =
                                                                            !context.getAnnotationDefaultValues(
                                                                                            annotationName)
                                                                                    .containsKey(
                                                                                            member
                                                                                                    .getName());
                                                                    if (context.getLanguage()
                                                                                    == VisitorContext
                                                                                            .Language
                                                                                            .JAVA
                                                                            && member
                                                                                            .getNativeType()
                                                                                    instanceof
                                                                                    ElementProvider
                                                                                            nativeElement
                                                                            && nativeElement
                                                                                            .element()
                                                                                    instanceof
                                                                                    javax.lang.model
                                                                                                    .element
                                                                                                    .ExecutableElement
                                                                                            nativeMember) {
                                                                        required =
                                                                                nativeMember
                                                                                                .getDefaultValue()
                                                                                        == null;
                                                                    }
                                                                    ExpressionDef info =
                                                                            ClassTypeDef.of(
                                                                                            AnnotationMember
                                                                                                    .class)
                                                                                    .instantiate(
                                                                                            List.of(
                                                                                                    TypeDef
                                                                                                            .CLASS,
                                                                                                    TypeDef
                                                                                                            .Primitive
                                                                                                            .BOOLEAN),
                                                                                            List.of(
                                                                                                    ExpressionDef
                                                                                                            .constant(
                                                                                                                    TypeDef
                                                                                                                            .erasure(
                                                                                                                                    member
                                                                                                                                            .getReturnType())),
                                                                                                    ExpressionDef
                                                                                                            .constant(
                                                                                                                    required)));
                                                                    entries.add(
                                                                            ClassTypeDef.of(
                                                                                            Map
                                                                                                    .class)
                                                                                    .invokeStatic(
                                                                                            "entry",
                                                                                            List.of(
                                                                                                    TypeDef
                                                                                                            .OBJECT,
                                                                                                    TypeDef
                                                                                                            .OBJECT),
                                                                                            TypeDef
                                                                                                    .of(
                                                                                                            Map
                                                                                                                    .Entry
                                                                                                                    .class),
                                                                                            List.of(
                                                                                                    ExpressionDef
                                                                                                            .constant(
                                                                                                                    member
                                                                                                                            .getName()),
                                                                                                    info)));
                                                                });
                                                ExpressionDef map =
                                                        ClassTypeDef.of(Map.class)
                                                                .invokeStatic(
                                                                        "ofEntries",
                                                                        List.of(
                                                                                TypeDef.of(
                                                                                        Map.Entry[]
                                                                                                .class)),
                                                                        TypeDef.of(Map.class),
                                                                        List.of(
                                                                                TypeDef.of(
                                                                                                Map
                                                                                                        .Entry
                                                                                                        .class)
                                                                                        .array()
                                                                                        .instantiate(
                                                                                                entries)));
                                                body.add(
                                                        parameters
                                                                .getFirst()
                                                                .invoke(
                                                                        "equals",
                                                                        List.of(TypeDef.OBJECT),
                                                                        TypeDef.Primitive.BOOLEAN,
                                                                        List.of(
                                                                                ExpressionDef
                                                                                        .constant(
                                                                                                annotationName)))
                                                                .ifTrue(map.returning()));
                                            });
                                    body.add(ExpressionDef.nullValue().returning());
                                    return StatementDef.multi(body);
                                }));
        Map<String, MethodDef> loadMethods = argumentLoadMethods;
        var loadClass =
                AnnotationMetadataGenUtils.createLoadClassValueExpressionFn(
                        ClassTypeDef.of(name), loadMethods);
        Map<String, FieldDef> constraintFields = new LinkedHashMap<>();
        List<StatementDef> defaults = new ArrayList<>();
        for (ClassElement annotation : found.values()) {
            if (!annotation.hasAnnotation("jakarta.validation.Constraint")) {
                continue;
            }
            FieldDef metadata =
                    AnnotationMetadataGenUtils.createAnnotationMetadataFieldAndInitialize(
                            annotation.getAnnotationMetadata(), loadClass);
            if (metadata == null) {
                continue;
            }
            ExpressionDef expression =
                    ConstraintDefinitionModels.create(
                            annotation, metadata.getInitializer().orElseThrow(), context);
            FieldDef definition =
                    FieldDef.builder(
                                    "$constraint" + constraintFields.size(),
                                    GeneratedConstraintDefinition.class)
                            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                            .initializer(expression)
                            .build();
            provider.addField(definition);
            constraintFields.put(annotation.getName(), definition);
            AnnotationMetadataGenUtils.addAnnotationDefaults(
                    defaults, annotation.getAnnotationMetadata(), loadClass);
        }
        loadMethods.values().forEach(provider::addMethod);
        if (!defaults.isEmpty()) {
            provider.addStaticInitializer(StatementDef.multi(defaults));
        }
        provider.addMethod(
                MethodDef.builder("definition")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(GeneratedConstraintDefinition.class)
                        .addParameter("name", String.class)
                        .build(
                                (aThis, parameters) -> {
                                    List<StatementDef> body = new ArrayList<>();
                                    constraintFields.forEach(
                                            (constraintName, field) ->
                                                    body.add(
                                                            parameters
                                                                    .getFirst()
                                                                    .invoke(
                                                                            "equals",
                                                                            List.of(TypeDef.OBJECT),
                                                                            TypeDef.Primitive
                                                                                    .BOOLEAN,
                                                                            List.of(
                                                                                    ExpressionDef
                                                                                            .constant(
                                                                                                    constraintName)))
                                                                    .ifTrue(
                                                                            ClassTypeDef.of(name)
                                                                                    .getStaticField(
                                                                                            field)
                                                                                    .returning())));
                                    body.add(ExpressionDef.nullValue().returning());
                                    return StatementDef.multi(body);
                                }));
        provider.addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).build());
        Map<String, ClassDef> implementations = new LinkedHashMap<>();
        int index = 0;
        for (ClassElement annotation : found.values()) {
            ClassDef implementation =
                    implementation(name + "$Annotation" + index++, annotation, context);
            implementations.put(annotation.getName(), implementation);
        }
        provider.addMethod(
                MethodDef.builder("create")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(Annotation.class)
                        .addParameter("value", AnnotationValue.class)
                        .build(
                                (aThis, parameters) -> {
                                    List<StatementDef> body = new ArrayList<>();
                                    ExpressionDef annotationName =
                                            parameters
                                                    .getFirst()
                                                    .invoke("getAnnotationName", TypeDef.STRING);
                                    implementations.forEach(
                                            (annotation, implementation) ->
                                                    body.add(
                                                            annotationName
                                                                    .invoke(
                                                                            "equals",
                                                                            List.of(TypeDef.OBJECT),
                                                                            TypeDef.Primitive
                                                                                    .BOOLEAN,
                                                                            List.of(
                                                                                    ExpressionDef
                                                                                            .constant(
                                                                                                    annotation)))
                                                                    .ifTrue(
                                                                            implementation
                                                                                    .asTypeDef()
                                                                                    .instantiate(
                                                                                            parameters
                                                                                                    .getFirst())
                                                                                    .returning())));
                                    body.add(ExpressionDef.nullValue().returning());
                                    return StatementDef.multi(body);
                                }));
        provider.addMethod(
                MethodDef.builder("attributes")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(Map.class)
                        .addParameter("value", AnnotationValue.class)
                        .build(
                                (aThis, parameters) -> {
                                    List<StatementDef> body = new ArrayList<>();
                                    ExpressionDef annotationName =
                                            parameters
                                                    .getFirst()
                                                    .invoke("getAnnotationName", TypeDef.STRING);
                                    implementations.forEach(
                                            (annotation, implementation) ->
                                                    body.add(
                                                            annotationName
                                                                    .invoke(
                                                                            "equals",
                                                                            List.of(TypeDef.OBJECT),
                                                                            TypeDef.Primitive
                                                                                    .BOOLEAN,
                                                                            List.of(
                                                                                    ExpressionDef
                                                                                            .constant(
                                                                                                    annotation)))
                                                                    .ifTrue(
                                                                            implementation
                                                                                    .asTypeDef()
                                                                                    .instantiate(
                                                                                            parameters
                                                                                                    .getFirst())
                                                                                    .invoke(
                                                                                            "$attributes",
                                                                                            TypeDef
                                                                                                    .of(
                                                                                                            Map
                                                                                                                    .class))
                                                                                    .returning())));
                                    body.add(ExpressionDef.nullValue().returning());
                                    return StatementDef.multi(body);
                                }));
        Map<String, ClassElement> enumTypes =
                enums.computeIfAbsent(element, ignored -> new LinkedHashMap<>());
        for (ClassElement annotation : found.values()) {
            for (MethodElement member : AnnotationMembers.of(annotation, context)) {
                ClassElement returned = member.getReturnType();
                if (returned.isArray()) {
                    returned = returned.fromArray();
                }
                if (returned instanceof EnumElement) {
                    enumTypes.put(returned.getName(), returned);
                }
            }
        }
        provider.addMethod(
                MethodDef.builder("enumConstants")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(List.class)
                        .addParameter("name", String.class)
                        .build(
                                (aThis, parameters) -> {
                                    List<StatementDef> body = new ArrayList<>();
                                    for (ClassElement type : enumTypes.values()) {
                                        if (!(type instanceof EnumElement enumeration)) {
                                            continue;
                                        }
                                        List<ExpressionDef> constants =
                                                enumeration.values().stream()
                                                        .map(
                                                                value ->
                                                                        (ExpressionDef)
                                                                                ClassTypeDef.of(
                                                                                                type)
                                                                                        .getStaticField(
                                                                                                value,
                                                                                                TypeDef
                                                                                                        .erasure(
                                                                                                                type)))
                                                        .toList();
                                        ExpressionDef values =
                                                ClassTypeDef.of(List.class)
                                                        .invokeStatic(
                                                                "of",
                                                                List.of(TypeDef.OBJECT.array()),
                                                                TypeDef.of(List.class),
                                                                List.of(
                                                                        TypeDef.OBJECT
                                                                                .array()
                                                                                .instantiate(
                                                                                        constants)));
                                        body.add(
                                                parameters
                                                        .getFirst()
                                                        .invoke(
                                                                "equals",
                                                                List.of(TypeDef.OBJECT),
                                                                TypeDef.Primitive.BOOLEAN,
                                                                List.of(
                                                                        ExpressionDef.constant(
                                                                                type.getName())))
                                                        .ifTrue(values.returning()));
                                    }
                                    body.add(ExpressionDef.nullValue().returning());
                                    return StatementDef.multi(body);
                                }));
        Map<String, String> allocators = new LinkedHashMap<>();
        Map<String, MethodDef> arrayMethods = new LinkedHashMap<>();
        List<ClassElement> components = new ArrayList<>(found.values());
        components.addAll(enumTypes.values());
        for (ClassElement annotation : components) {
            String allocatorName = "$annotationArray" + arrayMethods.size();
            TypeDef.Array array = TypeDef.erasure(annotation).array();
            MethodDef method =
                    MethodDef.builder(allocatorName)
                            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                            .returns(array)
                            .addParameter("size", int.class)
                            .build((aThis, parameters) -> array.instantiate(0).returning());
            provider.addMethod(method);
            arrayMethods.put(annotation.getName(), method);
            allocators.put(allocatorName, annotation.getName());
        }
        provider.addMethod(
                MethodDef.builder("array")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(Object[].class)
                        .addParameter("name", String.class)
                        .addParameter("size", int.class)
                        .build(
                                (aThis, parameters) -> {
                                    List<StatementDef> body = new ArrayList<>();
                                    arrayMethods.forEach(
                                            (annotation, allocator) ->
                                                    body.add(
                                                            parameters
                                                                    .getFirst()
                                                                    .invoke(
                                                                            "equals",
                                                                            List.of(TypeDef.OBJECT),
                                                                            TypeDef.Primitive
                                                                                    .BOOLEAN,
                                                                            List.of(
                                                                                    ExpressionDef
                                                                                            .constant(
                                                                                                    annotation)))
                                                                    .ifTrue(
                                                                            ClassTypeDef.of(name)
                                                                                    .invokeStatic(
                                                                                            allocator,
                                                                                            parameters
                                                                                                    .get(
                                                                                                            1))
                                                                                    .returning())));
                                    body.add(ExpressionDef.nullValue().returning());
                                    return StatementDef.multi(body);
                                }));
        var providerModel = provider.build();
        for (ClassDef implementation : implementations.values()) {
            new ByteCodeGenerator().write(implementation, context, element);
        }
        AnnotationArrayByteCodeGenerator.write(providerModel, allocators, context, element);
        context.visitServiceDescriptor(GeneratedAnnotationProvider.class.getName(), name, element);
    }

    private ExpressionDef supplementedTypeUse(
            ClassElement type,
            ExpressionDef original,
            Function<String, ExpressionDef> loadClass,
            Set<Object> visited) {
        if (type.isPrimitive() || !visited.add(type.getNativeType())) {
            return original;
        }
        List<ExpressionDef> parameters = new ArrayList<>();
        int index = 0;
        for (ClassElement nested : type.getTypeArguments().values()) {
            ExpressionDef parameter =
                    original.invoke("getTypeParameters", TypeDef.of(Argument[].class))
                            .arrayElement(index++);
            parameters.add(
                    supplementedTypeUse(
                            nested, parameter, loadClass, new java.util.HashSet<>(visited)));
        }
        var metadata =
                AnnotationMetadataGenUtils.instantiateNewMetadata(
                        MutableAnnotationMetadata.of(type.getTypeAnnotationMetadata()), loadClass);
        return ClassTypeDef.of(GeneratedAnnotationFactories.class)
                .invokeStatic(
                        "typeUse",
                        List.of(
                                TypeDef.of(Argument.class),
                                TypeDef.of(AnnotationMetadata.class),
                                TypeDef.of(Argument[].class)),
                        TypeDef.of(Argument.class),
                        List.of(
                                original,
                                metadata,
                                TypeDef.of(Argument.class).array().instantiate(parameters)));
    }

    private ClassDef implementation(String name, ClassElement annotation, VisitorContext context) {
        ClassTypeDef annotationType =
                InterfaceDef.builder(annotation.getName()).build().asTypeDef();
        ClassDef.ClassDefBuilder implementation =
                ClassDef.builder(name)
                        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                        .addAnnotation(AnnotationDef.builder(Internal.class).build())
                        .addSuperinterface(annotationType);
        List<MethodElement> members =
                AnnotationMembers.of(annotation, context).stream()
                        .filter(
                                method ->
                                        !method.isStatic()
                                                && method.getParameters().length == 0
                                                && method.getDeclaringType()
                                                        .getName()
                                                        .equals(annotation.getName()))
                        .toList();
        Map<String, FieldDef> fields = new LinkedHashMap<>();
        for (MethodElement member : members) {
            TypeDef type = TypeDef.erasure(member.getReturnType());
            FieldDef field =
                    FieldDef.builder(member.getName(), type)
                            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                            .build();
            fields.put(member.getName(), field);
            implementation.addField(field);
            implementation.addMethod(
                    MethodDef.builder(member.getName())
                            .addModifiers(Modifier.PUBLIC)
                            .returns(type)
                            .build(
                                    (aThis, parameters) ->
                                            type instanceof TypeDef.Array
                                                    ? copy(aThis.field(field), type).returning()
                                                    : aThis.field(field).returning()));
        }
        Map<CharSequence, Object> compilerDefaults =
                new LinkedHashMap<>(context.getAnnotationDefaultValues(annotation.getName()));
        for (MethodElement member : members) {
            if (context.getLanguage() == VisitorContext.Language.JAVA
                    && member.getNativeType() instanceof ElementProvider nativeElement
                    && nativeElement.element()
                            instanceof javax.lang.model.element.ExecutableElement executable
                    && executable.getDefaultValue() != null
                    && executable.getDefaultValue().getValue() instanceof String string) {
                compilerDefaults.put(member.getName(), string);
            }
        }
        var defaultsMetadata = new MutableAnnotationMetadata();
        defaultsMetadata.addDeclaredAnnotation(annotation.getName(), compilerDefaults);
        Map<String, MethodDef> defaultLoadMethods = new LinkedHashMap<>();
        var defaultsField =
                AnnotationMetadataGenUtils.createAnnotationMetadataFieldAndInitialize(
                        defaultsMetadata,
                        AnnotationMetadataGenUtils.createLoadClassValueExpressionFn(
                                ClassTypeDef.of(name), defaultLoadMethods));
        if (defaultsField != null) {
            implementation.addField(defaultsField);
        }
        defaultLoadMethods.values().forEach(implementation::addMethod);
        implementation.addMethod(
                MethodDef.constructor()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter("value", AnnotationValue.class)
                        .build(
                                (aThis, parameters) -> {
                                    List<StatementDef> body = new ArrayList<>();
                                    var effective =
                                            new io.micronaut.sourcegen.model.VariableDef.Local(
                                                    "effective", TypeDef.of(AnnotationValue.class));
                                    ExpressionDef effectiveValue =
                                            defaultsField == null
                                                    ? parameters.getFirst()
                                                    : ClassTypeDef.of(AnnotationMemberValues.class)
                                                            .invokeStatic(
                                                                    "withDefaults",
                                                                    List.of(
                                                                            TypeDef.of(
                                                                                    AnnotationValue
                                                                                            .class),
                                                                            TypeDef.of(
                                                                                    io.micronaut
                                                                                            .core
                                                                                            .annotation
                                                                                            .AnnotationMetadata
                                                                                            .class)),
                                                                    TypeDef.of(
                                                                            AnnotationValue.class),
                                                                    List.of(
                                                                            parameters.getFirst(),
                                                                            ClassTypeDef.of(name)
                                                                                    .getStaticField(
                                                                                            defaultsField)));
                                    body.add(effective.defineAndAssign(effectiveValue));
                                    for (MethodElement member : members) {
                                        FieldDef field =
                                                Objects.requireNonNull(
                                                        fields.get(member.getName()));
                                        ExpressionDef value =
                                                ClassTypeDef.of(AnnotationMemberValues.class)
                                                        .invokeStatic(
                                                                "required",
                                                                List.of(
                                                                        TypeDef.of(
                                                                                AnnotationValue
                                                                                        .class),
                                                                        TypeDef.STRING,
                                                                        TypeDef.CLASS),
                                                                TypeDef.OBJECT,
                                                                List.of(
                                                                        effective,
                                                                        ExpressionDef.constant(
                                                                                member.getName()),
                                                                        ExpressionDef.constant(
                                                                                field.getType())))
                                                        .cast(field.getType());
                                        if (member.getReturnType()
                                                        .getName()
                                                        .equals(String.class.getName())
                                                && !member.getReturnType().isArray()) {
                                            Object defaultValue =
                                                    context.getAnnotationDefaultValues(
                                                                    annotation.getName())
                                                            .get(member.getName());
                                            if (context.getLanguage()
                                                            == VisitorContext.Language.JAVA
                                                    && member.getNativeType()
                                                            instanceof ElementProvider nativeElement
                                                    && nativeElement.element()
                                                            instanceof
                                                            javax.lang.model.element
                                                                            .ExecutableElement
                                                                    executable
                                                    && executable.getDefaultValue() != null) {
                                                defaultValue =
                                                        executable.getDefaultValue().getValue();
                                            }
                                            value =
                                                    ClassTypeDef.of(AnnotationMemberValues.class)
                                                            .invokeStatic(
                                                                    "string",
                                                                    List.of(
                                                                            TypeDef.of(
                                                                                    AnnotationValue
                                                                                            .class),
                                                                            TypeDef.STRING,
                                                                            TypeDef.STRING),
                                                                    TypeDef.STRING,
                                                                    List.of(
                                                                            effective,
                                                                            ExpressionDef.constant(
                                                                                    member
                                                                                            .getName()),
                                                                            defaultValue
                                                                                            instanceof
                                                                                            String
                                                                                                    string
                                                                                    ? ExpressionDef
                                                                                            .constant(
                                                                                                    string)
                                                                                    : ExpressionDef
                                                                                            .nullValue()));
                                        }
                                        if (member.getReturnType() instanceof AnnotationElement) {
                                            value =
                                                    ClassTypeDef.of(AnnotationMemberValues.class)
                                                            .invokeStatic(
                                                                    "nested",
                                                                    List.of(
                                                                            TypeDef.of(
                                                                                    AnnotationValue
                                                                                            .class),
                                                                            TypeDef.STRING,
                                                                            TypeDef.CLASS),
                                                                    TypeDef.of(Annotation.class),
                                                                    List.of(
                                                                            effective,
                                                                            ExpressionDef.constant(
                                                                                    member
                                                                                            .getName()),
                                                                            ExpressionDef.constant(
                                                                                    field
                                                                                            .getType())))
                                                            .cast(field.getType());
                                        }
                                        if (member.getReturnType().isArray()
                                                && member.getReturnType().fromArray()
                                                        instanceof AnnotationElement nested) {
                                            value =
                                                    ClassTypeDef.of(AnnotationMemberValues.class)
                                                            .invokeStatic(
                                                                    "nestedArray",
                                                                    List.of(
                                                                            TypeDef.of(
                                                                                    AnnotationValue
                                                                                            .class),
                                                                            TypeDef.STRING,
                                                                            TypeDef.CLASS),
                                                                    TypeDef.of(Annotation[].class),
                                                                    List.of(
                                                                            effective,
                                                                            ExpressionDef.constant(
                                                                                    member
                                                                                            .getName()),
                                                                            ExpressionDef.constant(
                                                                                    TypeDef.erasure(
                                                                                            nested))))
                                                            .cast(field.getType());
                                        }
                                        if (member.getReturnType()
                                                instanceof EnumElement enumeration) {
                                            value =
                                                    ClassTypeDef.of(AnnotationMemberValues.class)
                                                            .invokeStatic(
                                                                    "enumValue",
                                                                    List.of(
                                                                            TypeDef.of(
                                                                                    AnnotationValue
                                                                                            .class),
                                                                            TypeDef.STRING,
                                                                            TypeDef.CLASS),
                                                                    TypeDef.of(Enum.class),
                                                                    List.of(
                                                                            effective,
                                                                            ExpressionDef.constant(
                                                                                    member
                                                                                            .getName()),
                                                                            ExpressionDef.constant(
                                                                                    TypeDef.erasure(
                                                                                            enumeration))))
                                                            .cast(field.getType());
                                        }
                                        if (member.getReturnType().isArray()
                                                && member.getReturnType().fromArray()
                                                        instanceof EnumElement enumeration) {
                                            value =
                                                    ClassTypeDef.of(AnnotationMemberValues.class)
                                                            .invokeStatic(
                                                                    "enumArray",
                                                                    List.of(
                                                                            TypeDef.of(
                                                                                    AnnotationValue
                                                                                            .class),
                                                                            TypeDef.STRING,
                                                                            TypeDef.CLASS),
                                                                    TypeDef.of(Object[].class),
                                                                    List.of(
                                                                            effective,
                                                                            ExpressionDef.constant(
                                                                                    member
                                                                                            .getName()),
                                                                            ExpressionDef.constant(
                                                                                    TypeDef.erasure(
                                                                                            enumeration))))
                                                            .cast(field.getType());
                                        }
                                        body.add(
                                                aThis.field(field)
                                                        .assign(
                                                                field.getType()
                                                                                instanceof
                                                                                TypeDef.Array
                                                                        ? copy(
                                                                                value,
                                                                                field.getType())
                                                                        : value));
                                    }
                                    return StatementDef.multi(body);
                                }));
        implementation.addMethod(
                MethodDef.builder("$attributes")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(Map.class)
                        .build(
                                (aThis, parameters) -> {
                                    List<ExpressionDef> entries = new ArrayList<>();
                                    for (FieldDef field : fields.values()) {
                                        ExpressionDef value =
                                                field.getType() instanceof TypeDef.Array
                                                        ? copy(aThis.field(field), field.getType())
                                                        : aThis.field(field);
                                        entries.add(
                                                ClassTypeDef.of(Map.class)
                                                        .invokeStatic(
                                                                "entry",
                                                                List.of(
                                                                        TypeDef.OBJECT,
                                                                        TypeDef.OBJECT),
                                                                TypeDef.of(Map.Entry.class),
                                                                List.of(
                                                                        ExpressionDef.constant(
                                                                                field.getName()),
                                                                        value)));
                                    }
                                    return ClassTypeDef.of(Map.class)
                                            .invokeStatic(
                                                    "ofEntries",
                                                    List.of(TypeDef.of(Map.Entry[].class)),
                                                    TypeDef.of(Map.class),
                                                    List.of(
                                                            TypeDef.of(Map.Entry.class)
                                                                    .array()
                                                                    .instantiate(entries)))
                                            .returning();
                                }));
        implementation.addMethod(
                MethodDef.builder("annotationType")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(Class.class)
                        .build(
                                (aThis, parameters) ->
                                        ExpressionDef.constant(annotationType).returning()));
        implementation.addMethod(
                MethodDef.builder("equals")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(boolean.class)
                        .addParameter("other", Object.class)
                        .build(
                                (aThis, parameters) -> {
                                    ExpressionDef.ConditionExpressionDef matches =
                                            parameters.getFirst().instanceOf(annotationType);
                                    for (MethodElement member : members) {
                                        FieldDef field =
                                                Objects.requireNonNull(
                                                        fields.get(member.getName()));
                                        matches =
                                                matches.and(
                                                        ClassTypeDef.of(Objects.class)
                                                                .invokeStatic(
                                                                        "deepEquals",
                                                                        List.of(
                                                                                TypeDef.OBJECT,
                                                                                TypeDef.OBJECT),
                                                                        TypeDef.Primitive.BOOLEAN,
                                                                        List.of(
                                                                                aThis.field(field),
                                                                                parameters
                                                                                        .getFirst()
                                                                                        .cast(
                                                                                                annotationType)
                                                                                        .invoke(
                                                                                                member
                                                                                                        .getName(),
                                                                                                field
                                                                                                        .getType())))
                                                                .isTrue());
                                    }
                                    return matches.returning();
                                }));
        implementation.addMethod(
                MethodDef.builder("hashCode")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(int.class)
                        .build(
                                (aThis, parameters) -> {
                                    ExpressionDef hash = ExpressionDef.constant(0);
                                    for (FieldDef field : fields.values()) {
                                        ExpressionDef memberHash =
                                                ClassTypeDef.of(AnnotationMemberValues.class)
                                                        .invokeStatic(
                                                                "hash",
                                                                List.of(TypeDef.OBJECT),
                                                                TypeDef.Primitive.INT,
                                                                List.of(aThis.field(field)));
                                        hash =
                                                hash.math(
                                                        ExpressionDef.MathBinaryOperation.OpType
                                                                .ADDITION,
                                                        ExpressionDef.constant(
                                                                        127
                                                                                * field.getName()
                                                                                        .hashCode())
                                                                .math(
                                                                        ExpressionDef
                                                                                .MathBinaryOperation
                                                                                .OpType.BITWISE_XOR,
                                                                        memberHash));
                                    }
                                    return hash.returning();
                                }));
        return implementation.build();
    }

    private static ExpressionDef copy(ExpressionDef value, TypeDef type) {
        return ClassTypeDef.of(AnnotationMemberValues.class)
                .invokeStatic("copy", List.of(TypeDef.OBJECT), TypeDef.OBJECT, List.of(value))
                .cast(type);
    }
}
