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
package io.micronaut.validation.validator.constraints;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.validation.validator.ReflectionSupport;
import io.micronaut.validation.validator.ValidationAnnotationUtil;
import io.micronaut.validation.validator.metadata.ValidationMetadataSupport;
import jakarta.validation.Constraint;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The constraints of an annotation metadata, including the ones repeated inside a Bean Validation
 * style container: an annotation holding the constraints, typically the nested {@code List}
 * predating {@code @Repeatable}. The generated metadata stores such a container as an ordinary
 * annotation, the contained constraints are read from its {@code value}.
 *
 * @since 5.3.0
 */
@Internal
public final class ConstraintContainers {

    private ConstraintContainers() { }

    /**
     * Whether the metadata carries a constraint, by stereotype or inside a container.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     * @param annotationMetadata The metadata
     * @param classLoader The loader of the constraint types
     * @return Whether there is a constraint
     */
    public static boolean hasConstraints(ReflectionSupport reflectionSupport, AnnotationMetadata annotationMetadata, ClassLoader classLoader) {
        if (annotationMetadata.hasStereotype(Constraint.class)) {
            return true;
        }
        for (String name : annotationMetadata.getAnnotationNames()) {
            if (containedConstraintType(reflectionSupport, annotationMetadata, name, classLoader) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the metadata carries a constraint, with the constraint types loaded by the context
     * class loader.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     * @param annotationMetadata The metadata
     * @return Whether there is a constraint
     */
    public static boolean hasConstraints(ReflectionSupport reflectionSupport, AnnotationMetadata annotationMetadata) {
        return hasConstraints(reflectionSupport, annotationMetadata, reflectionSupport.classLoader());
    }

    /**
     * Whether a constraint is declared on the element itself, directly or in the repeatable container the
     * metadata records for it, whatever its name.
     */
    private static boolean isDeclared(AnnotationMetadata annotationMetadata, Set<String> declared, String name) {
        return declared.contains(name)
            || declared.contains(annotationMetadata.findRepeatableAnnotation(name).orElse(name + "$List"));
    }

    /**
     * The constraint types of the metadata: the ones found by stereotype and the ones inside
     * containers.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     * @param annotationMetadata The metadata
     * @param classLoader The loader of the constraint types
     * @return The constraint types
     */
    public static Set<Class<? extends Annotation>> constraintTypes(ReflectionSupport reflectionSupport, AnnotationMetadata annotationMetadata, ClassLoader classLoader) {
        // resolved one by one rather than by AnnotationMetadata#getAnnotationTypesByStereotype, whose shared
        // registry keeps the first class seen under a name: a type deployed in several class loaders, as the
        // archives of a test suite are, has to be the one of the loader asking
        Set<Class<? extends Annotation>> types = new LinkedHashSet<>();
        List<String> names = constraintNames(reflectionSupport, annotationMetadata, classLoader);
        Set<String> declared = annotationMetadata.getDeclaredAnnotationNames();
        boolean hasDeclared = false;
        for (String name : names) {
            if (isDeclared(annotationMetadata, declared, name)) {
                hasDeclared = true;
                break;
            }
        }
        for (String name : names) {
            if (hasDeclared && !isDeclared(annotationMetadata, declared, name)) {
                continue;
            }
            AnnotationValue<?> occurrence = firstOccurrence(annotationMetadata, name);
            types.add(constraintType(reflectionSupport, occurrence != null ? occurrence : AnnotationValue.builder(name).build(), classLoader));
        }
        return types;
    }

    /**
     * The first occurrence of a constraint, declared or inside a container.
     */
    private static @Nullable AnnotationValue<?> firstOccurrence(AnnotationMetadata annotationMetadata, String name) {
        List<? extends AnnotationValue<?>> values = annotationMetadata.getAnnotationValuesByName(name);
        if (!values.isEmpty()) {
            return values.getFirst();
        }
        for (String containerName : annotationMetadata.getAnnotationNames()) {
            for (AnnotationValue<?> container : annotationMetadata.getAnnotationValuesByName(containerName)) {
                for (AnnotationValue<Annotation> value : container.getAnnotations(AnnotationMetadata.VALUE_MEMBER)) {
                    if (value.getAnnotationName().equals(name)) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The constraint names of the metadata: the ones found by stereotype and the ones inside
     * containers.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     * @param annotationMetadata The metadata
     * @param classLoader The loader of the constraint types
     * @return The constraint names
     */
    public static List<String> constraintNames(ReflectionSupport reflectionSupport, AnnotationMetadata annotationMetadata, ClassLoader classLoader) {
        List<String> names = new ArrayList<>(annotationMetadata.getAnnotationNamesByStereotype(Constraint.class));
        for (String name : annotationMetadata.getAnnotationNames()) {
            Class<? extends Annotation> contained = containedConstraintType(reflectionSupport, annotationMetadata, name, classLoader);
            if (contained != null && !names.contains(contained.getName())) {
                names.add(contained.getName());
            }
        }
        return names;
    }

    /**
     * The values of a constraint: the repeated ones, else the declared ones, else the ones of its
     * container.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     * @param annotationMetadata The metadata
     * @param constraintType The constraint type
     * @return The values, empty when the constraint is absent
     */
    public static List<? extends AnnotationValue<? extends Annotation>> values(ReflectionSupport reflectionSupport, AnnotationMetadata annotationMetadata,
                                                                              Class<? extends Annotation> constraintType) {
        List<? extends AnnotationValue<? extends Annotation>> values = annotationMetadata.getAnnotationValuesByType(constraintType);
        if (values.isEmpty()) {
            values = annotationMetadata.getDeclaredAnnotationValuesByType(constraintType);
        }
        if (values.isEmpty()) {
            for (String name : annotationMetadata.getAnnotationNames()) {
                AnnotationValue<?> container = annotationMetadata.getAnnotation(name);
                if (container != null) {
                    List<AnnotationValue<Annotation>> contained = container.getAnnotations(AnnotationMetadata.VALUE_MEMBER);
                    if (!contained.isEmpty() && contained.get(0).getAnnotationName().equals(constraintType.getName())) {
                        values = contained;
                        break;
                    }
                }
            }
        }
        List<AnnotationValue<? extends Annotation>> withValidators = new ArrayList<>(values.size());
        for (AnnotationValue<? extends Annotation> value : values) {
            withValidators.add(withValidators(reflectionSupport, value, constraintType));
        }
        return Collections.unmodifiableList(withValidators);
    }

    /**
     * The processor records the validators of a constraint in its value; a constraint nested in a
     * container or composing another is recorded as it is written, its validators are read from its
     * definition.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     * @param value The constraint value
     * @param constraintType The constraint type
     * @return The value with the validators of the constraint definition, the given one when it has
     * them
     */
    public static AnnotationValue<? extends Annotation> withValidators(ReflectionSupport reflectionSupport, AnnotationValue<? extends Annotation> value,
                                                                      Class<? extends Annotation> constraintType) {
        if (value.contains(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY)) {
            return value;
        }
        return reflectionSupport.withDeclaredValidators(value, constraintType);
    }

    /**
     * The constraint a container holds: an annotation whose {@code value} is a list of constraints
     * of one type, whatever its name — {@code X.List} by convention, though a container is free to
     * be named otherwise.
     */
    private static @Nullable Class<? extends Annotation> containedConstraintType(ReflectionSupport reflectionSupport, AnnotationMetadata annotationMetadata, String containerName, ClassLoader classLoader) {
        AnnotationValue<?> container = annotationMetadata.getAnnotation(containerName);
        if (container == null) {
            return null;
        }
        List<AnnotationValue<Annotation>> contained = container.getAnnotations(AnnotationMetadata.VALUE_MEMBER);
        if (contained.isEmpty()) {
            return null;
        }
        AnnotationValue<Annotation> first = contained.get(0);
        if (first.annotationClassValues(ValidationAnnotationUtil.CONSTRAINT_TYPE).length == 0
                && !reflectionSupport.isReflectionEnabled()) {
            return null;
        }
        Class<? extends Annotation> type = constraintType(reflectionSupport, first, classLoader);
        return isConstraint(reflectionSupport, first, type) ? type : null;
    }

    /**
     * Resolves a constraint through a compile-time class reference or the optional provider.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     * @param value The occurrence
     * @param classLoader The application's loader
     * @return Its annotation interface
     */
    public static Class<? extends Annotation> constraintType(ReflectionSupport reflectionSupport,
            AnnotationValue<?> value, ClassLoader classLoader) {
        for (var reference :
                value.annotationClassValues(ValidationAnnotationUtil.CONSTRAINT_TYPE)) {
            Class<?> type = reference.getType().orElse(null);
            if (type != null) {
                return (Class<? extends Annotation>) type;
            }
        }
        var standard = ValidationMetadataSupport.standardConstraint(value.getAnnotationName());
        return standard != null ? standard : (Class<? extends Annotation>)
            reflectionSupport.classForName(value.getAnnotationName(), classLoader);
    }

    /**
     * Whether an occurrence is one of a constraint: the contract is retained on it, which the
     * annotation processor sees to, and only metadata described reflectively - which records no
     * stereotypes - has the annotation type read for it.
     */
    private static boolean isConstraint(ReflectionSupport reflectionSupport, AnnotationValue<?> occurrence, Class<?> annotationType) {
        List<AnnotationValue<?>> stereotypes = occurrence.getStereotypes();
        if (stereotypes != null) {
            for (AnnotationValue<?> stereotype : stereotypes) {
                if (Constraint.class.getName().equals(stereotype.getAnnotationName())) {
                    return true;
                }
            }
        }
        return ValidationMetadataSupport.standardConstraint(occurrence.getAnnotationName()) != null
            || reflectionSupport.isConstraintAnnotation(annotationType);
    }

}
