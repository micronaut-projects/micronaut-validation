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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.validation.validator.metadata.GeneratedConstraintDefinition;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Models definition rules from the compiler's declaration metadata. */
final class ConstraintDefinitionModels {
    private ConstraintDefinitionModels() { }

    static ExpressionDef create(ClassElement type, ExpressionDef metadata, VisitorContext context) {
        Map<String, MethodElement> members = new LinkedHashMap<>();
        AnnotationMembers.of(type, context).stream()
                .filter(
                        method ->
                                method.getDeclaringType().getName().equals(type.getName())
                                        && method.getParameters().length == 0
                                        && !method.isStatic())
                .forEach(method -> members.put(method.getName(), method));
        String error = definitionError(type, members, context);
        String compositionError =
                context.getLanguage() == VisitorContext.Language.JAVA
                        ? JavaAnnotationDeclarations.compositionError(type)
                        : null;
        List<ExpressionDef> overrides = new ArrayList<>();
        for (MethodElement member : members.values()) {
            for (AnnotationValue<?> override :
                    member.getAnnotationValuesByName("jakarta.validation.OverridesAttribute")) {
                String constraint =
                        override.annotationClassValue("constraint").orElseThrow().getName();
                ClassElement composing = context.getClassElement(constraint).orElseThrow();
                String target =
                        override.stringValue("name")
                                .filter(name -> !name.isEmpty())
                                .orElse(member.getName());
                MethodElement overridden = composing.findMethod(target).orElse(null);
                String overrideError =
                        overridden == null
                                ? "Cannot override missing member " + constraint + "." + target
                                : !overridden
                                                        .getReturnType()
                                                        .getName()
                                                        .equals(member.getReturnType().getName())
                                                || overridden.getReturnType().isArray()
                                                        != member.getReturnType().isArray()
                                        ? "Overriding member has a different type: "
                                                + type.getName()
                                                + "."
                                                + member.getName()
                                        : null;
                overrides.add(
                        ClassTypeDef.of(GeneratedConstraintDefinition.MemberOverride.class)
                                .instantiate(
                                        List.of(
                                                TypeDef.STRING,
                                                TypeDef.CLASS,
                                                TypeDef.STRING,
                                                TypeDef.Primitive.INT,
                                                TypeDef.STRING),
                                        List.of(
                                                ExpressionDef.constant(member.getName()),
                                                ExpressionDef.constant(TypeDef.erasure(composing)),
                                                ExpressionDef.constant(target),
                                                ExpressionDef.constant(
                                                        override.intValue("constraintIndex")
                                                                .orElse(-1)),
                                                overrideError == null
                                                        ? ExpressionDef.nullValue()
                                                        : ExpressionDef.constant(overrideError))));
            }
        }
        ExpressionDef overrideList =
                ClassTypeDef.of(List.class)
                        .invokeStatic(
                                "of",
                                List.of(TypeDef.OBJECT.array()),
                                TypeDef.of(List.class),
                                List.of(TypeDef.OBJECT.array().instantiate(overrides)));
        return ClassTypeDef.of(GeneratedConstraintDefinition.class)
                .instantiate(
                        List.of(
                                TypeDef.CLASS,
                                TypeDef.of(io.micronaut.core.annotation.AnnotationMetadata.class),
                                TypeDef.STRING,
                                TypeDef.STRING,
                                TypeDef.of(List.class)),
                        List.of(
                                ExpressionDef.constant(TypeDef.erasure(type)),
                                metadata,
                                error == null
                                        ? ExpressionDef.nullValue()
                                        : ExpressionDef.constant(error),
                                compositionError == null
                                        ? ExpressionDef.nullValue()
                                        : ExpressionDef.constant(compositionError),
                                overrideList));
    }

    private static @Nullable String definitionError(
            ClassElement type, Map<String, MethodElement> members, VisitorContext context) {
        for (String name : members.keySet()) {
            if (name.startsWith("valid") && !name.equals("validationAppliesTo")) {
                return "Constraint member names must not start with 'valid': "
                        + type.getName()
                        + "."
                        + name;
            }
        }
        for (String name : List.of("message", "groups", "payload")) {
            MethodElement member = members.get(name);
            if (member == null) {
                return "Constraint must declare member '" + name + "': " + type.getName();
            }
            if (name.equals("message")) {
                if (!member.getReturnType().getName().equals(String.class.getName())) {
                    return "Constraint message member must return String: " + type.getName();
                }
            } else {
                if (!member.getReturnType().isArray()
                        || !member.getReturnType()
                                .fromArray()
                                .getName()
                                .equals(Class.class.getName())) {
                    return "Constraint " + name + " member must return Class[]: " + type.getName();
                }
                Object defaultValue = context.getAnnotationDefaultValues(type.getName()).get(name);
                if (!(defaultValue instanceof Object[] array) || array.length != 0) {
                    return "Constraint "
                            + name
                            + " member must default to an empty array: "
                            + type.getName();
                }
            }
        }
        boolean generic = false;
        int cross = 0;
        for (var validatorValue :
                java.util.Objects.requireNonNull(
                                type.getAnnotation("jakarta.validation.Constraint"))
                        .annotationClassValues("validatedBy")) {
            ClassElement validator =
                    context.getClassElement(validatorValue.getName()).orElseThrow();
            String[] targets =
                    validator.stringValues(
                            "jakarta.validation.constraintvalidation.SupportedValidationTarget");
            if (targets.length == 0
                    || java.util.Arrays.asList(targets).contains("ANNOTATED_ELEMENT")) {
                generic = true;
            }
            if (java.util.Arrays.asList(targets).contains("PARAMETERS")) {
                cross++;
                List<ClassElement> arguments =
                        new ArrayList<>(
                                validator
                                        .getTypeArguments("jakarta.validation.ConstraintValidator")
                                        .values());
                if (arguments.size() == 2
                        && !(arguments.get(1).getName().equals(Object.class.getName())
                                && (!arguments.get(1).isArray()
                                        || arguments.get(1).getArrayDimensions() == 1))) {
                    return "Cross-parameter validator must validate Object or Object[]: "
                            + type.getName();
                }
            }
        }
        if (cross > 1) {
            return "Cross-parameter constraints must not declare multiple cross-parameter"
                    + " validators: "
                    + type.getName();
        }
        MethodElement applies = members.get("validationAppliesTo");
        if (generic && cross > 0 && applies == null) {
            return "Generic and cross-parameter constraints must declare validationAppliesTo: "
                    + type.getName();
        }
        if (applies != null) {
            if (!generic || cross == 0) {
                return "validationAppliesTo is only allowed for generic and cross-parameter"
                        + " constraints: "
                        + type.getName();
            }
            if (!applies.getReturnType().getName().equals("jakarta.validation.ConstraintTarget")) {
                return "validationAppliesTo must return ConstraintTarget: " + type.getName();
            }
            if (!"IMPLICIT"
                    .equals(
                            String.valueOf(
                                    context.getAnnotationDefaultValues(type.getName())
                                            .get("validationAppliesTo")))) {
                return "validationAppliesTo must default to ConstraintTarget.IMPLICIT: "
                        + type.getName();
            }
        }
        return null;
    }
}
