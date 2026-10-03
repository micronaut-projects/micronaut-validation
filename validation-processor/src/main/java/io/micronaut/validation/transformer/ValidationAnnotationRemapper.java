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
package io.micronaut.validation.transformer;

import io.micronaut.annotation.processing.visitor.ElementProvider;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Retainable;
import io.micronaut.inject.annotation.AnnotationRemapper;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.EnumElement;
import io.micronaut.inject.processing.JavaModelUtils;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.validation.validator.ValidationAnnotationUtil;
import jakarta.validation.Constraint;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.Valid;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;

import java.lang.annotation.Inherited;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;

/**
 * The validation annotations remapper.
 *
 * @author Denis Stepanov
 */
public class ValidationAnnotationRemapper implements AnnotationRemapper {

    @Override
    public String getPackageName() {
        return AnnotationRemapper.ALL_PACKAGES;
    }

    @Override
    public List<AnnotationValue<?>> remap(AnnotationValue<?> annotation, VisitorContext visitorContext) {
        for (var group : annotation.annotationClassValues("groups")) {
            visitorContext.getClassElement(group.getName()).filter(ClassElement::isInterface)
                .filter(type -> !type.getName().startsWith("jakarta.validation."))
                .ifPresent(type -> type.annotate(Introspected.class));
        }
        var contained = annotation.getAnnotations(AnnotationMetadata.VALUE_MEMBER);
        if (!contained.isEmpty()) {
            var rewritten = contained.stream().map(value -> visitorContext.getClassElement(value.getAnnotationName())
                .map(type -> {
                    var contract = type.getAnnotationMetadata().getAnnotation(Constraint.class);
                    if (contract == null) {
                        return value;
                    }
                    var builder = value.mutate().stereotype(contract);
                    type.getAnnotationMetadata().findAnnotation(ReportAsSingleViolation.class).ifPresent(builder::stereotype);
                    for (String name : type.getAnnotationNamesByStereotype(Constraint.class)) {
                        type.getAnnotationValuesByName(name).forEach(builder::stereotype);
                    }
                    return retainedValue(remap(builder.build(), visitorContext).getFirst());
                }).orElse(value)).toArray(AnnotationValue<?>[]::new);
            annotation = annotation.mutate().member(AnnotationMetadata.VALUE_MEMBER, rewritten).build();
        }
        if (annotation.getAnnotationName().equals(Constraint.class.getName())) {
            // marking the contract itself makes every constraint annotation retainable, so an annotation
            // composing one keeps that occurrence attributed to it
            return List.of(
                    annotation.mutate().stereotype(
                            AnnotationValue.builder(Retainable.class).build()
                    ).build());
        }
        if (annotation.getAnnotationName().equals(ReportAsSingleViolation.class.getName())) {
            // the marker says a composition reports one violation rather than the ones it composes, and it is
            // read per constraint: retaining it keeps it on the occurrence, where the metadata answers for it
            return List.of(
                    annotation.mutate().stereotype(
                            AnnotationValue.builder(Retainable.class).build()
                    ).build());
        }
        if (annotation.getAnnotationName().equals(Valid.class.getName())) {
            return List.of(
                    annotation.mutate().stereotype(
                            AnnotationValue.builder(Inherited.class).build()
                    ).build());
        }
        List<AnnotationValue<?>> stereotypes = annotation.getStereotypes();
        if (stereotypes != null) {
            Optional<AnnotationValue<?>> optionalConstraint = stereotypes.stream().filter(stereotype -> stereotype.getAnnotationName().equals(Constraint.class.getName())).findFirst();
            if (optionalConstraint.isPresent()) {
                AnnotationValue<?> constraintAnnotationValue = optionalConstraint.get();
                AnnotationValueBuilder<?> builder = annotation.mutate()
                    .member(ValidationAnnotationUtil.CONSTRAINT_TYPE, new AnnotationClassValue<>(annotation.getAnnotationName()));
                if (annotation.getAnnotationName().equals("io.micronaut.validation.annotation.InEnum")
                    || annotation.getAnnotationName().equals("io.micronaut.validation.annotation.NotInEnum")) {
                    annotation.stringValue("value").flatMap(visitorContext::getClassElement)
                        .filter(EnumElement.class::isInstance).map(EnumElement.class::cast)
                        .ifPresent(type -> builder.member("$enumValues", type.values().toArray(String[]::new)));
                }
                AnnotationClassValue<?>[] validatedBy = constraintAnnotationValue.annotationClassValues("validatedBy");
                builder.member(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY, validatedBy);
                if (validatedBy.length > 0) {
                    var targets = new LinkedHashSet<String>();
                    for (var validator : validatedBy) {
                        visitorContext.getClassElement(validator.getName()).ifPresent(type -> {
                            var declared = type.getAnnotationMetadata().stringValues(SupportedValidationTarget.class);
                            if (declared.length == 0) {
                                targets.add("ANNOTATED_ELEMENT");
                            } else {
                                targets.addAll(List.of(declared));
                            }
                        });
                    }
                    builder.member(ValidationAnnotationUtil.VALIDATION_TARGETS, targets.toArray(String[]::new));
                }
                visitorContext.getClassElement(annotation.getAnnotationName()).ifPresent(type -> {
                    ConstraintDefinitionMetadata.retain(type, visitorContext, builder);
                    if (visitorContext.getLanguage() == VisitorContext.Language.JAVA) {
                        JavaComposition.retainDirectCompositions(type, builder);
                    }
                    builder.member(ValidationAnnotationUtil.REPORT_AS_SINGLE_VIOLATION,
                        type.hasDeclaredAnnotation(ReportAsSingleViolation.class));
                    if (hasDirectAndContainerComposition(type, visitorContext)) {
                        builder.member(ValidationAnnotationUtil.COMPOSITION_ERROR, "Constraint is composed both directly and in a container: " + type.getName());
                    }
                });
                return List.of(
                        builder.stereotype(
                        AnnotationValue.builder(Inherited.class).build()
                ).build());
            }
        }
        return List.of(annotation);
    }

    static int composingOccurrences(ClassElement type, String constraint, VisitorContext context) {
        if (context.getLanguage() == VisitorContext.Language.JAVA) {
            return JavaComposition.composingOccurrences(type, constraint);
        }
        int count = type.getAnnotationValuesByName(constraint).size();
        if (count == 0) {
            for (String annotation : type.getDeclaredAnnotationNames()) {
                for (var value : type.getAnnotationValuesByName(annotation)) {
                    count += (int) value.getAnnotations(AnnotationMetadata.VALUE_MEMBER).stream()
                        .filter(nested -> nested.getAnnotationName().equals(constraint)).count();
                }
            }
        }
        return count;
    }

    private static boolean hasDirectAndContainerComposition(ClassElement type, VisitorContext context) {
        if (context.getLanguage() == VisitorContext.Language.JAVA) {
            return JavaComposition.hasDirectAndContainerComposition(type);
        }
        var annotations = type.getAnnotationMetadata().getDeclaredAnnotationNames();
        // a nested container is named by its binary name, Size$List, as on the Java path
        return annotations.stream().anyMatch(name -> (name.endsWith("$List") || name.endsWith(".List"))
            && annotations.contains(name.substring(0, name.length() - 5)));
    }

    private static AnnotationValue<?> retainedValue(AnnotationValue<?> value) {
        var stereotypes = value.getStereotypes();
        if (stereotypes == null || stereotypes.isEmpty()) {
            return value;
        }
        // Nested annotation values do not pass through the metadata builder's retention step.
        return value.mutate().member(AnnotationUtil.STEREOTYPES_MEMBER,
            stereotypes.stream().map(ValidationAnnotationRemapper::retainedValue).toArray(AnnotationValue<?>[]::new)).build();
    }

    // Keep javac-only AST adapters out of Kotlin and Groovy frontend class loading.
    private static final class JavaComposition {
        private static void retainDirectCompositions(ClassElement type, AnnotationValueBuilder<?> occurrence) {
            if (type.getNativeType() instanceof ElementProvider provider
                && provider.element() instanceof TypeElement element) {
                // The retained stereotype list can include flattened descendants as well as direct constraints.
                String[] names = declaredAnnotations(element).stream().filter(JavaComposition::isConstraint)
                    .map(annotation -> JavaModelUtils.getClassName((TypeElement) annotation.getAnnotationType().asElement()))
                    .distinct().toArray(String[]::new);
                occurrence.member(ValidationAnnotationUtil.DIRECT_COMPOSING_CONSTRAINTS, names);
            }
        }

        private static boolean isConstraint(AnnotationMirror annotation) {
            return annotation.getAnnotationType().asElement().getAnnotationMirrors().stream()
                .anyMatch(marker -> marker.getAnnotationType().toString().equals("jakarta.validation.Constraint"));
        }

        private static int composingOccurrences(ClassElement type, String constraint) {
            if (type.getNativeType() instanceof ElementProvider provider
                && provider.element() instanceof TypeElement element) {
                // Read mirrors directly: the type's annotation metadata may still be being remapped.
                return (int) declaredAnnotations(element).stream()
                    .filter(annotation -> JavaModelUtils.getClassName((TypeElement) annotation.getAnnotationType().asElement()).equals(constraint))
                    .count();
            }
            return type.getAnnotationValuesByName(constraint).size();
        }

        private static List<AnnotationMirror> declaredAnnotations(TypeElement element) {
            var annotations = new ArrayList<AnnotationMirror>();
            for (var annotation : element.getAnnotationMirrors()) {
                annotations.add(annotation);
                for (var member : annotation.getElementValues().entrySet()) {
                    if (member.getKey().getSimpleName().contentEquals("value")
                        && member.getValue().getValue() instanceof List<?> nested) {
                        for (var value : nested) {
                            if (value instanceof javax.lang.model.element.AnnotationValue entry
                                && entry.getValue() instanceof AnnotationMirror mirror) {
                                annotations.add(mirror);
                            }
                        }
                    }
                }
            }
            return annotations;
        }

        private static boolean hasDirectAndContainerComposition(ClassElement type) {
            if (type.getNativeType() instanceof ElementProvider provider
                && provider.element() instanceof TypeElement element) {
                var annotations = element.getAnnotationMirrors().stream()
                    .map(annotation -> annotation.getAnnotationType().toString()).collect(Collectors.toSet());
                return annotations.stream().anyMatch(name -> name.endsWith(".List")
                    && annotations.contains(name.substring(0, name.length() - 5)));
            }
            return false;
        }
    }
}
