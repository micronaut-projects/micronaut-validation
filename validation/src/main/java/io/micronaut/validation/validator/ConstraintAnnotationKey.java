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
package io.micronaut.validation.validator;

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.validation.validator.constraints.ConstraintContainers;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class ConstraintAnnotationKey {

    private ConstraintAnnotationKey() {
    }

    static String of(Class<? extends Annotation> constraintType,
                     AnnotationValue<? extends Annotation> annotationValue) {
        Map<String, Object> attributes = new TreeMap<>();
        Map<CharSequence, Object> values = annotationValue.getValues();
        Map<CharSequence, Object> defaultValues = annotationValue.getDefaultValues();
        values.forEach((key, value) -> {
            String attributeName = key.toString();
            Object normalized = normalize(attributeName, value);
            if (!attributeName.startsWith("$") && isMeaningfulAttribute(attributeName, normalized)) {
                attributes.put(attributeName, normalized);
            }
        });
        if (defaultValues != null) {
            defaultValues.forEach((key, value) -> {
                String attributeName = key.toString();
                Object normalized = normalize(attributeName, value);
                if (!attributeName.startsWith("$") && isMeaningfulAttribute(attributeName, normalized)) {
                    attributes.putIfAbsent(attributeName, normalized);
                }
            });
        }
        return constraintType.getName() + attributes;
    }

    /**
     * Whether a constraint is declared on the element itself, directly or in its repeatable container. The
     * container is the one the metadata records for the constraint, whatever its name; metadata recording
     * none is read with the container named {@code List} nested in the constraint.
     */
    static boolean isDeclaredConstraint(AnnotationMetadata annotationMetadata, String constraintName) {
        return ConstraintContainers.isDeclared(annotationMetadata, constraintName);
    }

    static String container(AnnotationMetadata annotationMetadata, String constraintName) {
        return annotationMetadata.findRepeatableAnnotation(constraintName).orElse(constraintName + "$List");
    }

    private static boolean isMeaningfulAttribute(String attributeName, Object value) {
        return !"message".equals(attributeName) || !quoted("").equals(value);
    }

    private static Object normalize(String attributeName, Object value) {
        if (value instanceof Class<?> classValue) {
            return classValue.getName();
        }
        if (value instanceof Class<?>[] classValues) {
            return Arrays.stream(classValues)
                .map(Class::getName)
                .sorted()
                .toList();
        }
        if (value instanceof Object[] array) {
            List<Object> values = new ArrayList<>(array.length);
            for (Object element : array) {
                values.add(normalize(attributeName, element));
            }
            if ("groups".equals(attributeName) || "payload".equals(attributeName)) {
                values.sort((left, right) -> String.valueOf(left).compareTo(String.valueOf(right)));
            }
            return values;
        }
        if (value.getClass().isArray() && value.getClass().getComponentType().isPrimitive()) {
            return primitiveArrayToString(value);
        }
        if (value instanceof AnnotationClassValue<?> classValue) {
            return classValue.getName();
        }
        return quoted(String.valueOf(value));
    }

    /**
     * A text of a key, prefixed with its length: texts holding the separators of the key, {"a, b"} against
     * {"a", "b"}, or a text spelling an array do not run into another key.
     */
    private static String quoted(String text) {
        return text.length() + ":" + text;
    }

    private static String primitiveArrayToString(Object value) {
        if (value instanceof boolean[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof byte[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof char[] array) {
            return quoted(String.valueOf(array));
        }
        if (value instanceof double[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof float[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof int[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof long[] array) {
            return Arrays.toString(array);
        }
        return Arrays.toString((short[]) value);
    }
}
