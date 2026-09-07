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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.reflection.ReflectionAnnotations;
import io.micronaut.reflection.ReflectionArguments;
import jakarta.validation.ValidationException;
import jakarta.validation.valueextraction.ValueExtractor;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code ValueExtractor} signature a class declares, for an extractor the specification API hands over as
 * an instance. Nothing generated describes an instance registered at runtime, so its class is read.
 *
 * @since 5.2
 */
@Internal
final class ReflectedValueExtractors {

    private ReflectedValueExtractors() {
    }

    /**
     * The argument describing the extractor: the signature its class declares, with the annotations of that
     * signature and of the class itself.
     *
     * @param extractorType The class of the extractor instance
     * @return The argument
     */
    static Argument<?> argumentOf(Class<?> extractorType) {
        List<AnnotatedType> signatures = new ArrayList<>();
        collectSignatures(signatures, extractorType);
        if (signatures.size() != 1) {
            throw new ValidationException("Expected one ValueExtractor signature on " + extractorType.getName()
                + ", found: " + signatures);
        }
        Argument<?> argument = ReflectionArguments.of(signatures.get(0));
        AnnotationMetadata declared = ReflectionAnnotations.metadataOf(extractorType);
        if (declared.isEmpty()) {
            return argument;
        }
        return Argument.of(
            argument.getType(),
            new AnnotationMetadataHierarchy(argument.getAnnotationMetadata(), declared),
            argument.getTypeParameters());
    }

    private static void collectSignatures(List<AnnotatedType> signatures, Class<?> type) {
        if (!ValueExtractor.class.isAssignableFrom(type)) {
            return;
        }
        Class<?> superClass = type.getSuperclass();
        if (superClass != null && !Object.class.equals(superClass)) {
            collectSignatures(signatures, superClass);
        }
        for (Class<?> implementedInterface : type.getInterfaces()) {
            if (!ValueExtractor.class.equals(implementedInterface)) {
                collectSignatures(signatures, implementedInterface);
            }
        }
        for (AnnotatedType annotatedInterface : type.getAnnotatedInterfaces()) {
            if (ValueExtractor.class.equals(erasureOf(annotatedInterface.getType()))) {
                signatures.add(annotatedInterface);
            }
        }
    }

    private static Class<?> erasureOf(Type type) {
        if (type instanceof Class<?> classType) {
            return classType;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return erasureOf(parameterizedType.getRawType());
        }
        if (type instanceof GenericArrayType) {
            return Object[].class;
        }
        if (type instanceof WildcardType wildcardType) {
            return erasureOf(wildcardType.getUpperBounds()[0]);
        }
        throw new IllegalArgumentException("Unknown type: " + type);
    }
}
