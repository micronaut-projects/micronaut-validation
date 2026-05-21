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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;
import jakarta.inject.Singleton;
import jakarta.validation.Constraint;
import jakarta.validation.metadata.BeanDescriptor;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private static void addConstraintAnnotations(MutableAnnotationMetadata metadata,
                                                 AnnotatedElement element,
                                                 Set<String> excludedAnnotationNames) {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType.isAnnotationPresent(Constraint.class)) {
                if (!excludedAnnotationNames.contains(annotationType.getName())) {
                    addConstraintAnnotation(metadata, annotation, null);
                }
            } else {
                containedConstraints(annotation)
                    .stream()
                    .filter(contained -> !excludedAnnotationNames.contains(contained.annotationType().getName()))
                    .forEach(contained -> addConstraintAnnotation(metadata, contained, annotationType.getName()));
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

    private static void addConstraintAnnotation(MutableAnnotationMetadata metadata, Annotation annotation, @Nullable String containerName) {
        String annotationName = annotation.annotationType().getName();
        Map<CharSequence, Object> defaultValues = annotationDefaultValues(annotation.annotationType());
        metadata.addDefaultAnnotationValues(annotationName, defaultValues);
        if (containerName == null) {
            metadata.addDeclaredAnnotation(annotationName, annotationValues(annotation, true));
        } else {
            metadata.addDeclaredRepeatable(
                containerName,
                new AnnotationValue<>(annotationName, annotationValues(annotation, true), defaultValues)
            );
        }
        metadata.addDeclaredStereotype(List.of(annotationName), Constraint.class.getName(), Map.of());
    }

    private static List<Annotation> containedConstraints(Annotation container) {
        try {
            Method valueMethod = container.annotationType().getDeclaredMethod("value");
            if (!valueMethod.getReturnType().isArray() || !Annotation.class.isAssignableFrom(valueMethod.getReturnType().getComponentType())) {
                return List.of();
            }
            valueMethod.setAccessible(true);
            Annotation[] annotations = (Annotation[]) valueMethod.invoke(container);
            return Arrays.stream(annotations)
                .filter(annotation -> annotation.annotationType().isAnnotationPresent(Constraint.class))
                .filter(annotation -> !annotation.annotationType().isAnnotationPresent(Repeatable.class))
                .toList();
        } catch (NoSuchMethodException e) {
            return List.of();
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new jakarta.validation.ValidationException("Cannot read constraint container " + container.annotationType().getName(), e);
        }
    }

    private static Map<CharSequence, Object> annotationValues(Annotation annotation) {
        return annotationValues(annotation, false);
    }

    private static Map<CharSequence, Object> annotationValues(Annotation annotation, boolean includeDefaults) {
        Map<CharSequence, Object> values = new LinkedHashMap<>();
        Class<? extends Annotation> annotationType = annotation.annotationType();
        for (Method method : annotationType.getDeclaredMethods()) {
            try {
                method.setAccessible(true);
                Object value = method.invoke(annotation);
                if (value != null && (includeDefaults || !Objects.deepEquals(value, method.getDefaultValue()))) {
                    values.put(method.getName(), value);
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new jakarta.validation.ValidationException("Cannot read constraint annotation " + annotationType.getName(), e);
            }
        }
        return values;
    }

    private static Map<CharSequence, Object> annotationDefaultValues(Class<? extends Annotation> annotationType) {
        Map<CharSequence, Object> values = new LinkedHashMap<>();
        for (Method method : annotationType.getDeclaredMethods()) {
            Object value = method.getDefaultValue();
            if (value != null) {
                values.put(method.getName(), value);
            }
        }
        return values;
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
