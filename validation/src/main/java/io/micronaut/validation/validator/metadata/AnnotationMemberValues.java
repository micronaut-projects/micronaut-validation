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
package io.micronaut.validation.validator.metadata;

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Typed operations shared by generated annotation implementations.
 *
 * @since 5.3.0
 */
@Internal
@NullMarked
public final class AnnotationMemberValues {
    private AnnotationMemberValues() { }

    /**
     * Adds generated defaults without changing the supplied occurrence.
     *
     * @param value The occurrence
     * @param metadata Compiler-declared defaults
     * @return An occurrence
     * with its exact defaults
     */
    public static AnnotationValue<?> withDefaults(
            AnnotationValue<?> value, AnnotationMetadata metadata) {
        var declaration = metadata.getAnnotation(value.getAnnotationName());
        if (declaration == null) {
            return value;
        }
        var defaults = new LinkedHashMap<CharSequence, Object>(declaration.getValues());
        if (value.getDefaultValues() != null) {
            defaults.putAll(value.getDefaultValues());
        }
        return new AnnotationValue<>(value.getAnnotationName(), value.getValues(), defaults);
    }

    /**
     * Reads a string attribute using its generated default.
     *
     * @param value The occurrence
     * @param name The member
     * @param defaultValue Its compiler-declared
     * default
     * @return The exact string
     */
    public static String string(
            AnnotationValue<?> value,
            String name,
            @Nullable String defaultValue) {
        Object raw = value.getValues().get(name);
        if (raw instanceof String string) {
            return string;
        }
        if (defaultValue != null) {
            return defaultValue;
        }
        return (String) required(value, name, String.class);
    }

    /**
     * Reads an explicitly empty string without conversion discarding it.
     *
     * @param value The occurrence
     * @param name The member
     * @param type The declared type
     * @return The typed member value
     */
    public static Object required(AnnotationValue<?> value, String name, Class<?> type) {
        Object raw = value.getValues().get(name);
        if (raw == null && value.getDefaultValues() != null) {
            raw = value.getDefaultValues().get(name);
        }
        if (raw != null && type.isInstance(raw)) {
            return raw;
        }
        if (type == Class.class && raw instanceof AnnotationClassValue<?> reference) {
            return classReference(reference);
        }
        if (type == Class[].class && raw instanceof Object[] references) {
            Class<?>[] classes = new Class<?>[references.length];
            for (int i = 0; i < references.length; i++) {
                Object reference = references[i];
                if (reference instanceof Class<?> clazz) {
                    classes[i] = clazz;
                } else if (reference instanceof AnnotationClassValue<?> acv) {
                    classes[i] = classReference(acv);
                } else {
                    throw new jakarta.validation.ValidationException(
                            "Missing generated class reference for " + reference);
                }
            }
            return classes;
        }
        return value.getRequiredValue(name, type);
    }

    private static Class<?> classReference(AnnotationClassValue<?> reference) {
        return reference
                .getType()
                .orElseThrow(
                        () ->
                                new jakarta.validation.ValidationException(
                                        "No generated class reference for "
                                                + reference.getName()
                                                + ": add micronaut-validation-reflection"));
    }

    /**
     * Reads an enum attribute through generated constants.
     *
     * @param value The occurrence
     * @param name The member
     * @param type The enum type
     * @return A
     * generated enum value
     */
    public static Enum<?> enumValue(AnnotationValue<?> value, String name, Class<?> type) {
        Object raw = value.getValues().get(name);
        if (raw == null && value.getDefaultValues() != null) {
            raw = value.getDefaultValues().get(name);
        }
        if (raw instanceof Enum<?> constant) {
            return constant;
        }
        for (Enum<?> constant : GeneratedAnnotationFactories.enumConstants(type)) {
            if (constant.name().equals(String.valueOf(raw))) {
                return constant;
            }
        }
        throw new jakarta.validation.ValidationException(
                "Unknown enum member " + name + " of " + value.getAnnotationName());
    }

    /**
     * Copies an array of generated enum constants.
     *
     * @param value The occurrence
     * @param name The member
     * @param type The enum type
     * @return
     * Independent enum values
     */
    public static Object[] enumArray(AnnotationValue<?> value, String name, Class<?> type) {
        Object raw = value.getValues().get(name);
        if (raw == null && value.getDefaultValues() != null) {
            raw = value.getDefaultValues().get(name);
        }
        Object[] values =
                raw instanceof Object[] array
                        ? array
                        : raw == null ? new Object[0] : new Object[] {raw};
        Object[] result = GeneratedAnnotationFactories.typedArray(type, values.length);
        var constants = GeneratedAnnotationFactories.enumConstants(type);
        for (int i = 0; i < values.length; i++) {
            Object item = values[i];
            if (item instanceof Enum<?> constant) {
                result[i] = constant;
                continue;
            }
            result[i] =
                    constants.stream()
                            .filter(constant -> constant.name().equals(String.valueOf(item)))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new jakarta.validation.ValidationException(
                                                    "Unknown enum value " + item));
        }
        return result;
    }

    /**
     * Copies mutable annotation attribute arrays.
     *
     * @param value The value to copy
     * @return An independent array, or the immutable scalar
     */
    public static Object copy(Object value) {
        if (value instanceof Object[] array) {
            return array.clone();
        }
        if (value instanceof boolean[] array) {
            return array.clone();
        }
        if (value instanceof byte[] array) {
            return array.clone();
        }
        if (value instanceof short[] array) {
            return array.clone();
        }
        if (value instanceof char[] array) {
            return array.clone();
        }
        if (value instanceof int[] array) {
            return array.clone();
        }
        if (value instanceof long[] array) {
            return array.clone();
        }
        if (value instanceof float[] array) {
            return array.clone();
        }
        if (value instanceof double[] array) {
            return array.clone();
        }
        return value;
    }

    /**
     * Computes the annotation member hash required by the Java annotation contract.
     *
     * @param value The member value
     * @return Its annotation-contract hash
     */
    public static int hash(Object value) {
        if (value instanceof Object[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof boolean[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof byte[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof short[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof char[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof int[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof long[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof float[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof double[] array) {
            return Arrays.hashCode(array);
        }
        return value.hashCode();
    }

    /**
     * Creates a concrete nested annotation.
     *
     * @param value The attributes
     * @param name The nested member name
     * @param type The annotation interface
     * @return The concrete nested annotation
     */
    public static Annotation nested(
            AnnotationValue<?> value, String name, Class<? extends Annotation> type) {
        AnnotationValue<?> nested = value.getRequiredValue(name, AnnotationValue.class);
        return GeneratedAnnotationFactories.create(type, nested);
    }

    /**
     * Creates concrete nested annotations in a defensive array.
     *
     * @param value The attributes
     * @param name The nested array member
     * @param type The annotation component type
     * @return Independent concrete annotations in a typed array
     */
    public static Annotation[] nestedArray(
            AnnotationValue<?> value, String name, Class<? extends Annotation> type) {
        Object raw = value.getValues().get(name);
        if (raw == null && value.getDefaultValues() != null) {
            raw = value.getDefaultValues().get(name);
        }
        List<? extends AnnotationValue<?>> values;
        if (raw instanceof AnnotationValue<?>[] array) {
            values = Arrays.asList(array);
        } else if (raw instanceof AnnotationValue<?> annotation) {
            values = List.of(annotation);
        } else if (raw instanceof List<?> list) {
            values = (List<? extends AnnotationValue<?>>) list;
        } else {
            throw new jakarta.validation.ValidationException(
                    "Missing annotation array member " + name);
        }
        Annotation[] result = GeneratedAnnotationFactories.array(type, values.size());
        for (int i = 0; i < result.length; i++) {
            result[i] = GeneratedAnnotationFactories.create(type, values.get(i));
        }
        return result;
    }
}
