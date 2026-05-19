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
import java.lang.reflect.Array;
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
        annotationValue.getValues().forEach((key, value) -> attributes.put(key.toString(), normalize(value)));
        Map<CharSequence, Object> defaultValues = annotationValue.getDefaultValues();
        if (defaultValues != null) {
            defaultValues.forEach((key, value) -> attributes.putIfAbsent(key.toString(), normalize(value)));
        }
        return constraintType.getName() + attributes;
    }

    private static Object normalize(Object value) {
        if (value instanceof Class<?> classValue) {
            return classValue.getName();
        }
        if (value instanceof Class<?>[] classValues) {
            return Arrays.stream(classValues)
                .map(Class::getName)
                .sorted()
                .toList();
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> values = new ArrayList<>(Array.getLength(value));
            for (int i = 0; i < Array.getLength(value); i++) {
                values.add(normalize(Array.get(value, i)));
            }
            return values;
        }
        return String.valueOf(value)
            .replace("interface ", "")
            .replace("class ", "");
    }
}
