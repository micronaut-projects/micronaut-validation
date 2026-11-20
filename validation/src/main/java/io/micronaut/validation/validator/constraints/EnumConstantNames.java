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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.validation.validator.DefaultConstraintValidatorContext;
import io.micronaut.validation.validator.ReflectionSupport;
import io.micronaut.validation.validator.ValidationAnnotationUtil;
import io.micronaut.validation.validator.metadata.ValidationMetadataSupport;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The names of the constants of the enum an enum constraint names: the ones compiled into the constraint, else the
 * ones its access provider reads, read once per provider and enum.
 *
 * @since 5.3.0
 */
final class EnumConstantNames {

    private final Map<Key, List<String>> names = new ConcurrentHashMap<>();

    List<String> names(AnnotationValue<? extends Annotation> annotationValue,
                       Class<? extends Enum<?>> enumClass,
                       ConstraintValidatorContext context) {
        if (annotationValue.contains(ValidationAnnotationUtil.ENUM_VALUES)) {
            return List.of(annotationValue.stringValues(ValidationAnnotationUtil.ENUM_VALUES));
        }
        ReflectionSupport reflectionSupport = context instanceof DefaultConstraintValidatorContext<?> validationContext
            ? validationContext.reflectionSupport() : ReflectionSupport.get();
        return names.computeIfAbsent(new Key(reflectionSupport, enumClass), key -> {
            List<? extends Enum<?>> constants = ValidationMetadataSupport.enumConstants(key.reflectionSupport(), key.enumClass());
            List<String> constantNames = new ArrayList<>(constants.size());
            for (Enum<?> constant : constants) {
                constantNames.add(constant.name());
            }
            return List.copyOf(constantNames);
        });
    }

    private record Key(ReflectionSupport reflectionSupport, Class<? extends Enum<?>> enumClass) {
    }
}
