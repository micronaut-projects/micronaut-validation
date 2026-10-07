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
package io.micronaut.validation.transformer;

import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.validation.visitor.ValidationVisitor;
import io.micronaut.validation.validator.ValidationAnnotationUtil;
import jakarta.validation.Constraint;
import jakarta.validation.OverridesAttribute;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Retains definition diagnostics and attributes requiring concrete runtime values. */
final class ConstraintDefinitionMetadata {
    static final String STRICT_OPTION = ValidationVisitor.STRICT_DEFINITIONS_OPTION;

    private static final String REPORTED = ConstraintDefinitionMetadata.class.getName() + ".reported";

    private ConstraintDefinitionMetadata() { }

    /** Reports an invalid definition where it is compiled, once for a constraint however often it is used. */
    @SuppressWarnings("unchecked")
    private static void report(ClassElement type, VisitorContext context, String error) {
        Set<String> reported = context.get(REPORTED, Set.class).orElse(null);
        if (reported == null) {
            reported = new HashSet<>();
            context.put(REPORTED, reported);
        }
        if (!reported.add(type.getName() + ": " + error)) {
            return;
        }
        if (Boolean.parseBoolean(context.getOptions().get(STRICT_OPTION))) {
            context.fail(error, type);
        } else {
            context.warn(error + ". The constraint fails with a ConstraintDefinitionException when a validator checking"
                + " constraint definitions validates it; set the annotation processor option " + STRICT_OPTION
                + "=true to fail the compilation instead", type);
        }
    }

    static void retain(ClassElement type, VisitorContext context, AnnotationValueBuilder<?> occurrence) {
        var members = new LinkedHashMap<String, MethodElement>();
        type.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared()).forEach(member -> members.put(member.getName(), member));
        Map<CharSequence, Object> defaults = context.getAnnotationDefaultValues(type.getName());
        var declaredValues = occurrence.build();
        List<String> runtimeAttributes = new ArrayList<>();
        List<String> patternFlags = new ArrayList<>();
        List<String> patternFlagArrays = new ArrayList<>();
        for (var member : members.values()) {
            var result = member.getReturnType();
            var component = result.isArray() ? result.fromArray() : result;
            boolean generatedTarget = member.getName().equals("validationAppliesTo") && !result.isArray()
                && component.getName().equals("jakarta.validation.ConstraintTarget");
            boolean generatedFlags = component.getName().equals("jakarta.validation.constraints.Pattern$Flag");
            if (generatedFlags) {
                (result.isArray() ? patternFlagArrays : patternFlags).add(member.getName());
                Object defaultValue = defaults.get(member.getName());
                if (!declaredValues.contains(member.getName()) && defaultValue != null) {
                    // Custom enum defaults are not necessarily registered in the runtime annotation defaults registry.
                    occurrence.members(Map.of(member.getName(), defaultValue));
                }
            }
            if ((component.isEnum() || component.isAssignable("java.lang.annotation.Annotation"))
                && !generatedTarget && !generatedFlags) {
                runtimeAttributes.add(member.getName());
            }
        }
        occurrence.member(ValidationAnnotationUtil.PATTERN_FLAGS, patternFlags.toArray(String[]::new));
        occurrence.member(ValidationAnnotationUtil.PATTERN_FLAG_ARRAYS, patternFlagArrays.toArray(String[]::new));
        occurrence.member(ValidationAnnotationUtil.RUNTIME_ATTRIBUTES, runtimeAttributes.toArray(String[]::new));
        var contract = type.getAnnotationMetadata().getAnnotation(Constraint.class);
        if (contract == null) {
            return;
        }
        for (var reference : contract.annotationClassValues("validatedBy")) {
            if (context.getClassElement(reference.getName()).isEmpty()) {
                // Incomplete metadata must leave runtime definition checks available to the companion.
                return;
            }
        }
        occurrence.member(ValidationAnnotationUtil.DEFINITION_CHECKED, true);
        String error = definitionError(type, context, members, defaults);
        String compositionError = compositionError(type, context, members);
        if (compositionError != null) {
            report(type, context, compositionError);
            occurrence.member(ValidationAnnotationUtil.COMPOSITION_DEFINITION_ERROR, compositionError);
        }
        if (error != null) {
            report(type, context, error);
            // the specification times the failure at validation, so the error is also retained for it
            occurrence.member(ValidationAnnotationUtil.DEFINITION_ERROR, error);
        }
    }

    private static @Nullable String definitionError(ClassElement type, VisitorContext context,
                                          Map<String, MethodElement> members, Map<CharSequence, Object> defaults) {
        for (String name : members.keySet()) {
            if (name.startsWith("valid") && !name.equals("validationAppliesTo")) {
                return "Constraint member names must not start with 'valid': " + type.getName() + "." + name;
            }
        }
        for (var required : Map.of("message", "java.lang.String", "groups", "java.lang.Class[]", "payload", "java.lang.Class[]").entrySet()) {
            var member = members.get(required.getKey());
            if (member == null || !name(member.getReturnType()).equals(required.getValue())) {
                return "Invalid constraint member " + type.getName() + "." + required.getKey() + ": expected " + required.getValue();
            }
        }
        for (String name : List.of("groups", "payload")) {
            Object value = defaults.get(name);
            if (!(value instanceof Object[] array) || array.length != 0) {
                return "Constraint " + name + " member must default to an empty array: " + type.getName();
            }
        }
        boolean generic = false;
        int crossParameter = 0;
        var contract = type.getAnnotationMetadata().getAnnotation(Constraint.class);
        if (contract != null) {
            for (var reference : contract.annotationClassValues("validatedBy")) {
                var validator = context.getClassElement(reference.getName()).orElse(null);
                if (validator == null) {
                    return "Cannot resolve constraint validator " + reference.getName();
                }
                List<String> targets = List.of(validator.stringValues(SupportedValidationTarget.class));
                generic |= targets.isEmpty() || targets.contains("ANNOTATED_ELEMENT");
                if (targets.contains("PARAMETERS")) {
                    crossParameter++;
                    var arguments = validator.getTypeArguments("jakarta.validation.ConstraintValidator");
                    var value = arguments.get("T");
                    if (value != null && !name(value).equals("java.lang.Object") && !name(value).equals("java.lang.Object[]")) {
                        return "Cross-parameter validator must validate Object or Object[]: " + type.getName();
                    }
                }
            }
        }
        var target = members.get("validationAppliesTo");
        if (target != null && (!name(target.getReturnType()).equals("jakarta.validation.ConstraintTarget")
            || !"IMPLICIT".equals(String.valueOf(defaults.get("validationAppliesTo"))))) {
            return "validationAppliesTo must return ConstraintTarget and default to IMPLICIT: " + type.getName();
        }
        if (crossParameter > 1) {
            return "Cross-parameter constraints must not declare multiple cross-parameter validators: " + type.getName();
        }
        if ((generic && crossParameter > 0) != (target != null)) {
            return "validationAppliesTo is required only for constraints that are both generic and cross-parameter: " + type.getName();
        }
        return null;
    }

    private static @Nullable String compositionError(ClassElement type, VisitorContext context,
                                                      Map<String, MethodElement> members) {
        for (var member : members.values()) {
            for (var override : member.getAnnotationValuesByType(OverridesAttribute.class)) {
                String constraint = override.annotationClassValue("constraint").orElseThrow().getName();
                String name = override.stringValue("name").filter(value -> !value.isEmpty()).orElse(member.getName());
                var composing = context.getClassElement(constraint).orElse(null);
                var overridden = composing == null ? null : composing.getMethods().stream()
                    .filter(candidate -> candidate.getName().equals(name)).findFirst().orElse(null);
                if (overridden == null) {
                    return "Cannot override the missing member " + constraint + "." + name + " from " + type.getName() + "." + member.getName();
                }
                if (!name(overridden.getReturnType()).equals(name(member.getReturnType()))) {
                    return "The member of " + type.getName() + " overriding " + constraint + "." + name + " does not have the type of that member";
                }
                int count = ValidationAnnotationRemapper.composingOccurrences(type, constraint, context);
                int index = override.intValue("constraintIndex").orElse(-1);
                if (count == 0 || index >= count || (index < 0 && count > 1)) {
                    return "Invalid composition occurrence for " + type.getName() + "." + member.getName();
                }
            }
        }
        return null;
    }

    private static String name(ClassElement type) {
        return type.isArray() ? name(type.fromArray()) + "[]" : type.getName();
    }
}
