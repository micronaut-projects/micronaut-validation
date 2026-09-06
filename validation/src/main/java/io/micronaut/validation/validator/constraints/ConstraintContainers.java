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

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.validation.validator.ValidationAnnotationUtil;
import jakarta.validation.Constraint;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The constraints of an annotation metadata, including the ones repeated inside a Bean Validation style
 * container: an annotation holding the constraints, typically the nested {@code List} predating {@code @Repeatable}.
 * The generated metadata stores such a container as an ordinary annotation, the contained constraints are
 * read from its {@code value}.
 *
 * @since 5.0.0
 */
@Internal
public final class ConstraintContainers {

    private ConstraintContainers() {
    }

    /**
     * Whether the metadata carries a constraint, by stereotype or inside a container.
     *
     * @param annotationMetadata The metadata
     * @param classLoader        The loader of the constraint types
     * @return Whether there is a constraint
     */
    public static boolean hasConstraints(@NonNull AnnotationMetadata annotationMetadata, @NonNull ClassLoader classLoader) {
        if (annotationMetadata.hasStereotype(Constraint.class)) {
            return true;
        }
        for (String name : annotationMetadata.getAnnotationNames()) {
            if (containedConstraintType(annotationMetadata, name, classLoader) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the metadata carries a constraint, with the constraint types loaded by the context class loader.
     *
     * @param annotationMetadata The metadata
     * @return Whether there is a constraint
     */
    public static boolean hasConstraints(@NonNull AnnotationMetadata annotationMetadata) {
        return hasConstraints(annotationMetadata, contextClassLoader());
    }

    /**
     * The constraint types of the metadata: the ones found by stereotype and the ones inside containers.
     *
     * @param annotationMetadata The metadata
     * @param classLoader        The loader of the constraint types
     * @return The constraint types
     */
    @NonNull
    @SuppressWarnings("unchecked")
    public static Set<Class<? extends Annotation>> constraintTypes(@NonNull AnnotationMetadata annotationMetadata, @NonNull ClassLoader classLoader) {
        // the names are resolved one by one instead of by AnnotationMetadata#getAnnotationTypesByStereotype:
        // the shared registry of the annotation types keeps the first class seen under a name, and a type
        // deployed several times in different class loaders — the archives of a test suite — has to be the
        // one of the loader asking
        Set<Class<? extends Annotation>> types = new LinkedHashSet<>();
        for (String name : constraintNames(annotationMetadata, classLoader)) {
            ClassUtils.forName(name, classLoader)
                .filter(Class::isAnnotation)
                .ifPresent(type -> types.add((Class<? extends Annotation>) type));
        }
        return types;
    }

    /**
     * The constraint names of the metadata: the ones found by stereotype and the ones inside containers.
     *
     * @param annotationMetadata The metadata
     * @param classLoader        The loader of the constraint types
     * @return The constraint names
     */
    @NonNull
    public static List<String> constraintNames(@NonNull AnnotationMetadata annotationMetadata, @NonNull ClassLoader classLoader) {
        List<String> names = new ArrayList<>(annotationMetadata.getAnnotationNamesByStereotype(Constraint.class));
        for (String name : annotationMetadata.getAnnotationNames()) {
            Class<? extends Annotation> contained = containedConstraintType(annotationMetadata, name, classLoader);
            if (contained != null && !names.contains(contained.getName())) {
                names.add(contained.getName());
            }
        }
        return names;
    }

    /**
     * The values of a constraint: the repeated ones, else the declared ones, else the ones of its container.
     *
     * @param annotationMetadata The metadata
     * @param constraintType     The constraint type
     * @return The values, empty when the constraint is absent
     */
    @NonNull
    public static List<? extends AnnotationValue<? extends Annotation>> values(@NonNull AnnotationMetadata annotationMetadata,
                                                                              @NonNull Class<? extends Annotation> constraintType) {
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
        return values.stream().map(value -> withValidators(value, constraintType)).toList();
    }

    /**
     * The processor records the validators of a constraint in its value; a constraint nested in a container
     * or composing another is recorded as it is written, its validators are read from its definition.
     *
     * @param value          The constraint value
     * @param constraintType The constraint type
     * @return The value with the validators of the constraint definition, the given one when it has them
     */
    @NonNull
    public static AnnotationValue<? extends Annotation> withValidators(@NonNull AnnotationValue<? extends Annotation> value,
                                                                      @NonNull Class<? extends Annotation> constraintType) {
        if (value.contains(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY)) {
            return value;
        }
        Constraint constraint = constraintType.getAnnotation(Constraint.class);
        if (constraint == null || constraint.validatedBy().length == 0) {
            return value;
        }
        AnnotationClassValue<?>[] validators = Arrays.stream(constraint.validatedBy())
            .map(AnnotationClassValue::new)
            .toArray(AnnotationClassValue[]::new);
        return AnnotationValue.builder(value).member(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY, validators).build();
    }

    /**
     * The constraint a container holds: an annotation whose {@code value} is a list of constraints of one type,
     * whatever its name — {@code X.List} by convention, though a container is free to be named otherwise.
     */
    private static Class<? extends Annotation> containedConstraintType(AnnotationMetadata annotationMetadata, String containerName, ClassLoader classLoader) {
        AnnotationValue<?> container = annotationMetadata.getAnnotation(containerName);
        if (container == null) {
            return null;
        }
        List<AnnotationValue<Annotation>> contained = container.getAnnotations(AnnotationMetadata.VALUE_MEMBER);
        if (contained.isEmpty()) {
            return null;
        }
        String constraintName = contained.get(0).getAnnotationName();
        return ClassUtils.forName(constraintName, classLoader)
            .filter(type -> type.isAnnotation() && type.isAnnotationPresent(Constraint.class))
            .<Class<? extends Annotation>>map(type -> (Class<? extends Annotation>) type)
            .orElse(null);
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        return classLoader == null ? ConstraintContainers.class.getClassLoader() : classLoader;
    }
}
