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
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.annotation.ReflectionAnnotationMetadataBuilder;
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;
import jakarta.inject.Singleton;
import jakarta.validation.Constraint;
import jakarta.validation.metadata.BeanDescriptor;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Internal reflection metadata provider fallback for the optional Jakarta
 * compliance stack.
 *
 * @since 5.1
 */
@Internal
@Singleton
@Requires(property = ReflectionValidator.ENABLED, notEquals = StringUtils.FALSE, defaultValue = StringUtils.TRUE)
public final class ReflectionValidationMetadataProvider implements ValidationMetadataProvider {

    /**
     * Creates a reflection metadata provider.
     */
    public ReflectionValidationMetadataProvider() {
        // Public no-arg constructor required for bean construction.
    }

    @Override
    public Optional<BeanDescriptor> getConstraintsForClass(Class<?> beanType) {
        return Optional.of(ReflectionValidator.ReflectionBeanMetadata.of(beanType));
    }

    @Override
    public AnnotationMetadata getBeanAnnotationMetadata(Class<?> beanType) {
        return annotationMetadata(beanType);
    }

    @Override
    public AnnotationMetadata getPropertyAnnotationMetadata(Class<?> beanType, String propertyName) {
        MutableAnnotationMetadata metadata = new MutableAnnotationMetadata();
        Set<String> generatedConstraints = generatedPropertyConstraints(beanType, propertyName);
        for (Class<?> current = beanType; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(propertyName)) {
                    addConstraintAnnotations(metadata, field, generatedConstraints);
                }
            }
            for (Method method : current.getDeclaredMethods()) {
                if (propertyName.equals(propertyName(method))) {
                    addConstraintAnnotations(metadata, method, generatedConstraints);
                }
            }
        }
        return metadata.isEmpty() ? AnnotationMetadata.EMPTY_METADATA : metadata;
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }

    private static AnnotationMetadata annotationMetadata(AnnotatedElement element) {
        MutableAnnotationMetadata metadata = new MutableAnnotationMetadata();
        addConstraintAnnotations(metadata, element);
        return metadata.isEmpty() ? AnnotationMetadata.EMPTY_METADATA : metadata;
    }

    private static void addConstraintAnnotations(MutableAnnotationMetadata metadata, AnnotatedElement element) {
        addConstraintAnnotations(metadata, element, Set.of());
    }

    /**
     * Adds the constraints of an element, directly present or inside a repeatable container, through the
     * {@link ReflectionAnnotationMetadataBuilder} of micronaut-core, so that the metadata has the shape of the
     * generated one: the {@code Constraint} stereotype, the container of a repeatable constraint, the defaults.
     */
    private static void addConstraintAnnotations(MutableAnnotationMetadata metadata,
                                                 AnnotatedElement element,
                                                 Set<String> excludedAnnotationNames) {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(Constraint.class)) {
                if (!excludedAnnotationNames.contains(annotation.annotationType().getName())) {
                    ReflectionAnnotationMetadataBuilder.add(metadata, annotation, true);
                }
                continue;
            }
            for (Annotation contained : ReflectionAnnotationMetadataBuilder.contained(annotation)) {
                if (contained.annotationType().isAnnotationPresent(Constraint.class)
                    && !excludedAnnotationNames.contains(contained.annotationType().getName())) {
                    ReflectionAnnotationMetadataBuilder.add(metadata, contained, true);
                }
            }
        }
    }

    private static Set<String> generatedPropertyConstraints(Class<?> beanType, String propertyName) {
        return BeanIntrospector.SHARED.findIntrospection(beanType)
            .flatMap(introspection -> introspection.getProperty(propertyName))
            .map(property -> property.getAnnotationMetadata()
                .getAnnotationTypesByStereotype(Constraint.class)
                .stream()
                .map(Class::getName)
                .collect(Collectors.toUnmodifiableSet()))
            .orElse(Set.of());
    }

    @Nullable
    private static String propertyName(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        if (name.startsWith("is") && name.length() > 2 && method.getReturnType() == boolean.class) {
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        return null;
    }
}
