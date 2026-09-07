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
package io.micronaut.validation.reflection;

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.reflection.ReflectionAnnotations;
import io.micronaut.validation.validator.ReflectionSupport;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import io.micronaut.validation.validator.constraints.ConstraintValidatorTargetResolver;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.OverridesAttribute;
import jakarta.validation.constraintvalidation.ValidationTarget;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The constraints a constraint composes, read off its annotation type: the composing annotations of the type,
 * the ones a repeatable container holds included, with the attributes the composed constraint overrides
 * through {@link OverridesAttribute}, its groups and payload, and the validators of each resolved.
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
final class ReflectedComposition {

    private static final String ATTRIBUTE_VALIDATION_APPLIES_TO = "validationAppliesTo";
    private static final String ATTRIBUTE_GROUPS = "groups";
    private static final String ATTRIBUTE_PAYLOAD = "payload";

    private ReflectedComposition() {
    }

    /**
     * The constraints a constraint composes, read off the annotation type. A constraint the processors never saw
     * carries no retained tree - the type of a library, and every type the Jakarta Validation TCK declares - and
     * reading the class back is the only way to describe what it composes.
     *
     * @param constraintType        The composed constraint type
     * @param parentAnnotationValue The occurrence of the composed constraint
     * @return The composing constraints, in declaration order
     */
    static List<ReflectionSupport.ComposingConstraint> composingConstraints(
        Class<? extends Annotation> constraintType,
        AnnotationValue<? extends Annotation> parentAnnotationValue) {
        List<ComposingAnnotation> composingAnnotations = composingAnnotations(constraintType);
        checkCompositionTargets(constraintType, composingAnnotations);
        List<ReflectionSupport.ComposingConstraint> composingConstraints = new ArrayList<>();
        for (ComposingAnnotation annotation : composingAnnotations) {
            composingConstraints.add(composingConstraint(annotation, constraintType, parentAnnotationValue, composingAnnotations));
        }
        return List.copyOf(composingConstraints);
    }

    /**
     * A composed constraint and the constraints composing it share a validation target: generic, cross-parameter
     * or both.
     */
    private static void checkCompositionTargets(Class<? extends Annotation> parentType, List<ComposingAnnotation> composingAnnotations) {
        if (composingAnnotations.isEmpty()) {
            return;
        }
        Set<ValidationTarget> common = EnumSet.copyOf(validationTargets(parentType));
        for (ComposingAnnotation composingAnnotation : composingAnnotations) {
            common.retainAll(validationTargets(composingAnnotation.annotation().annotationType()));
            if (common.isEmpty()) {
                throw new ConstraintDefinitionException("Composing constraints must share a validation target with the composed constraint: " + parentType.getName());
            }
        }
    }

    private static Set<ValidationTarget> validationTargets(Class<? extends Annotation> annotationType) {
        jakarta.validation.Constraint constraint = annotationType.getAnnotation(jakarta.validation.Constraint.class);
        if (constraint == null || constraint.validatedBy().length == 0) {
            return EnumSet.of(ValidationTarget.ANNOTATED_ELEMENT, ValidationTarget.PARAMETERS);
        }
        Set<ValidationTarget> targets = EnumSet.noneOf(ValidationTarget.class);
        for (Class<?> validator : constraint.validatedBy()) {
            Set<ValidationTarget> supported = ConstraintValidatorTargetResolver.validationTargets(validator);
            if (supported.isEmpty()) {
                // a validator declaring no target validates the annotated element
                targets.add(ValidationTarget.ANNOTATED_ELEMENT);
            } else {
                targets.addAll(supported);
            }
        }
        return targets;
    }

    @SuppressWarnings("unchecked")
    private static ReflectionSupport.ComposingConstraint composingConstraint(
        ComposingAnnotation composingAnnotation,
        Class<? extends Annotation> parentType,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        List<ComposingAnnotation> composingAnnotations) {
        Annotation annotation = composingAnnotation.annotation();
        Class<? extends Annotation> annotationType = annotation.annotationType();
        Map<CharSequence, Object> values = ReflectionAnnotations.values(annotation);
        applyOverrides(annotationType, composingAnnotation.constraintIndex(), parentType, parentAnnotationValue, values, composingAnnotations);
        // the target of the composed constraint applies to the composing ones declaring one
        ConstraintTarget validationAppliesTo = parentAnnotationValue.enumValue(ATTRIBUTE_VALIDATION_APPLIES_TO, ConstraintTarget.class).orElse(ConstraintTarget.IMPLICIT);
        if (validationAppliesTo != ConstraintTarget.IMPLICIT && hasMember(annotationType, ATTRIBUTE_VALIDATION_APPLIES_TO)) {
            values.put(ATTRIBUTE_VALIDATION_APPLIES_TO, validationAppliesTo);
        }
        values.put(ATTRIBUTE_GROUPS, parentAnnotationValue.classValues(ATTRIBUTE_GROUPS));
        values.put(ATTRIBUTE_PAYLOAD, parentAnnotationValue.classValues(ATTRIBUTE_PAYLOAD));
        AnnotationValue<Annotation> annotationValue = (AnnotationValue<Annotation>) ConstraintContainers.withValidators(
            new AnnotationValue<>(annotationType.getName(), values, ReflectionAnnotations.defaultValues(annotationType)),
            annotationType
        );
        return new ReflectionSupport.ComposingConstraint(annotationType, annotationValue);
    }

    private static void applyOverrides(
        Class<? extends Annotation> composingType,
        int composingConstraintIndex,
        Class<? extends Annotation> parentType,
        AnnotationValue<? extends Annotation> parentAnnotationValue,
        Map<CharSequence, Object> values,
        List<ComposingAnnotation> composingAnnotations) {
        Map<String, Object> parentAttributes = attributes(parentAnnotationValue);
        for (Method method : parentType.getDeclaredMethods()) {
            Object value = parentAttributes.get(method.getName());
            if (value == null) {
                continue;
            }
            OverridesAttribute override = method.getAnnotation(OverridesAttribute.class);
            if (override != null) {
                applyOverride(composingType, composingConstraintIndex, values, method, value, override, composingAnnotations);
            }
            OverridesAttribute.List overrides = method.getAnnotation(OverridesAttribute.List.class);
            if (overrides != null) {
                for (OverridesAttribute listedOverride : overrides.value()) {
                    applyOverride(composingType, composingConstraintIndex, values, method, value, listedOverride, composingAnnotations);
                }
            }
        }
    }

    private static void applyOverride(
        Class<? extends Annotation> composingType,
        int composingConstraintIndex,
        Map<CharSequence, Object> values,
        Method method,
        Object value,
        OverridesAttribute override,
        List<ComposingAnnotation> composingAnnotations) {
        if (override.constraint() != composingType) {
            return;
        }
        long occurrences = composingAnnotations.stream().filter(annotation -> annotation.annotation().annotationType() == composingType).count();
        if (override.constraintIndex() >= occurrences) {
            throw new ConstraintDefinitionException("Invalid constraintIndex " + override.constraintIndex() + " overriding " + composingType.getName() + " in " + method.getDeclaringClass().getName());
        }
        if (override.constraintIndex() == -1 || override.constraintIndex() == composingConstraintIndex) {
            String name = override.name().isEmpty() ? method.getName() : override.name();
            checkOverride(method, value, composingType, name);
            values.put(name, value);
        }
    }

    /**
     * An overriding member has the type of the member it overrides.
     */
    private static void checkOverride(Method method, Object value, Class<? extends Annotation> composingType, String memberName) {
        Method member;
        try {
            member = composingType.getDeclaredMethod(memberName);
        } catch (NoSuchMethodException e) {
            throw new ConstraintDefinitionException("Cannot override the missing member " + composingType.getName() + "." + memberName + " from " + method.getDeclaringClass().getName(), e);
        }
        if (!isAssignableToMember(value, member.getReturnType())) {
            throw new ConstraintDefinitionException("The member " + method.getDeclaringClass().getName() + "." + method.getName() + " does not have the type of " + composingType.getName() + "." + memberName);
        }
    }

    /**
     * Whether a value, as the metadata stores it, fits a member type: a class is stored as its name, an enum
     * as its constant name, a primitive as its wrapper.
     */
    private static boolean isAssignableToMember(Object value, Class<?> memberType) {
        if (memberType.isArray()) {
            Class<?> valueType = value.getClass();
            if (valueType.isArray()) {
                return isAssignableToMember(Array.getLength(value) == 0 ? null : Array.get(value, 0), memberType.getComponentType());
            }
            return isAssignableToMember(value, memberType.getComponentType());
        }
        if (value == null) {
            return true;
        }
        if (memberType == Class.class) {
            return value instanceof Class || value instanceof AnnotationClassValue;
        }
        if (memberType.isEnum()) {
            return memberType.isInstance(value) || value instanceof String;
        }
        if (memberType.isAnnotation()) {
            return memberType.isInstance(value) || value instanceof AnnotationValue;
        }
        if (memberType.isPrimitive()) {
            return ReflectionUtils.getWrapperType(memberType) == value.getClass();
        }
        return memberType.isAssignableFrom(value.getClass());
    }

    private static boolean hasMember(Class<? extends Annotation> annotationType, String memberName) {
        for (Method method : annotationType.getDeclaredMethods()) {
            if (method.getName().equals(memberName) && method.getParameterCount() == 0) {
                return true;
            }
        }
        return false;
    }

    private static List<ComposingAnnotation> composingAnnotations(Class<? extends Annotation> constraintType) {
        List<ComposingAnnotation> composingAnnotations = new ArrayList<>();
        Map<Class<? extends Annotation>, Integer> constraintIndexes = new LinkedHashMap<>();
        Set<Class<? extends Annotation>> direct = new LinkedHashSet<>();
        Set<Class<? extends Annotation>> contained = new LinkedHashSet<>();
        for (Annotation annotation : constraintType.getDeclaredAnnotations()) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType.isAnnotationPresent(jakarta.validation.Constraint.class)) {
                if (contained.contains(annotationType)) {
                    throw new ConstraintDeclarationException("A constraint composes " + annotationType.getName() + " both directly and in a container: " + constraintType.getName());
                }
                direct.add(annotationType);
                int constraintIndex = constraintIndexes.merge(annotationType, 0, (previous, ignored) -> previous + 1);
                composingAnnotations.add(new ComposingAnnotation(annotation, constraintIndex));
                continue;
            }
            for (Annotation repeatedAnnotation : repeatedConstraintAnnotations(annotation)) {
                Class<? extends Annotation> repeatedAnnotationType = repeatedAnnotation.annotationType();
                if (direct.contains(repeatedAnnotationType)) {
                    throw new ConstraintDeclarationException("A constraint composes " + repeatedAnnotationType.getName() + " both directly and in a container: " + constraintType.getName());
                }
                contained.add(repeatedAnnotationType);
                int constraintIndex = constraintIndexes.merge(repeatedAnnotationType, 0, (previous, ignored) -> previous + 1);
                composingAnnotations.add(new ComposingAnnotation(repeatedAnnotation, constraintIndex));
            }
        }
        return composingAnnotations;
    }

    private static Map<String, Object> attributes(AnnotationValue<? extends Annotation> annotationValue) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        annotationValue.getValues().forEach((key, value) -> attributes.put(key.toString(), value));
        Map<CharSequence, Object> defaultValues = annotationValue.getDefaultValues();
        if (defaultValues != null) {
            defaultValues.forEach((key, value) -> attributes.putIfAbsent(key.toString(), value));
        }
        return attributes;
    }

    /**
     * The constraints a repeatable container composes: the annotations the container holds that are constraints.
     */
    private static List<Annotation> repeatedConstraintAnnotations(Annotation annotation) {
        List<Annotation> constraints = new ArrayList<>();
        for (Annotation repeatedAnnotation : ReflectionAnnotations.contained(annotation)) {
            if (repeatedAnnotation.annotationType().isAnnotationPresent(jakarta.validation.Constraint.class)) {
                constraints.add(repeatedAnnotation);
            }
        }
        return constraints;
    }

    private record ComposingAnnotation(
        Annotation annotation,
        int constraintIndex
    ) {
    }
}
