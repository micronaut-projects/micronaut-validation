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

import io.micronaut.core.annotation.AnnotationValue;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    static boolean isDeclaredConstraint(Set<String> declaredAnnotationNames,
                                        Class<? extends Annotation> constraintType) {
        String constraintName = constraintType.getName();
        return declaredAnnotationNames.contains(constraintName)
            || declaredAnnotationNames.contains(constraintName + "$List");
    }

    private static boolean isMeaningfulAttribute(String attributeName, Object value) {
        return !"message".equals(attributeName) || !"".equals(value);
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
        if (value instanceof boolean[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof byte[] array) {
            return Arrays.toString(array);
        }
        if (value instanceof char[] array) {
            return Arrays.toString(array);
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
        if (value instanceof short[] array) {
            return Arrays.toString(array);
        }
        return String.valueOf(value)
            .replace("interface ", "")
            .replace("class ", "");
    }
}
