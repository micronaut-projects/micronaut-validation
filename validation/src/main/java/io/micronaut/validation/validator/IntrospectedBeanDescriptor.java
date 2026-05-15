/*
 * Copyright 2017-2020 original authors
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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.util.ArgumentUtils;
import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ConstructorDescriptor;
import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.ElementDescriptor;
import jakarta.validation.metadata.GroupConversionDescriptor;
import jakarta.validation.metadata.MethodDescriptor;
import jakarta.validation.metadata.MethodType;
import jakarta.validation.metadata.PropertyDescriptor;
import jakarta.validation.metadata.Scope;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Basic implementation of {@link BeanDescriptor} that uses bean introspection metadata.
 *
 * @author graemerocher
 * @since 1.2.0
 */
@Internal
class IntrospectedBeanDescriptor implements BeanDescriptor, ElementDescriptor.ConstraintFinder {

    private final BeanIntrospection<?> beanIntrospection;
    private final AnnotationMetadata beanAnnotationMetadata;
    private final Map<String, AnnotationMetadata> propertyAnnotationMetadata;

    /**
     * Default constructor.
     *
     * @param beanIntrospection The bean introspection
     */
    IntrospectedBeanDescriptor(BeanIntrospection<?> beanIntrospection) {
        this(beanIntrospection, beanIntrospection.getAnnotationMetadata(), Collections.emptyMap());
    }

    /**
     * @param beanIntrospection The bean introspection
     * @param beanAnnotationMetadata The bean annotation metadata
     * @param propertyAnnotationMetadata The property annotation metadata
     */
    IntrospectedBeanDescriptor(BeanIntrospection<?> beanIntrospection,
                               AnnotationMetadata beanAnnotationMetadata,
                               Map<String, AnnotationMetadata> propertyAnnotationMetadata) {
        ArgumentUtils.requireNonNull("beanIntrospection", beanIntrospection);
        this.beanIntrospection = beanIntrospection;
        this.beanAnnotationMetadata = beanAnnotationMetadata;
        this.propertyAnnotationMetadata = new LinkedHashMap<>(propertyAnnotationMetadata);
    }

    @Override
    public boolean isBeanConstrained() {
        return hasConstraints() || getConstrainedProperties().stream().anyMatch(property -> property.hasConstraints() || property.isCascaded());
    }

    @Override
    public PropertyDescriptor getConstraintsForProperty(String propertyName) {
        return beanIntrospection.getProperty(propertyName)
            .map(IntrospectedPropertyDescriptor::new)
            .orElse(null);
    }

    @Override
    public Set<PropertyDescriptor> getConstrainedProperties() {
        return beanIntrospection.getBeanProperties()
            .stream()
            .map(IntrospectedPropertyDescriptor::new)
            .filter(property -> property.hasConstraints() || property.isCascaded())
            .collect(Collectors.toSet());
    }

    @Override
    public MethodDescriptor getConstraintsForMethod(String methodName, Class<?>... parameterTypes) {
        return null;
    }

    @Override
    public Set<MethodDescriptor> getConstrainedMethods(MethodType methodType, MethodType... methodTypes) {
        return Collections.emptySet();
    }

    @Override
    public ConstructorDescriptor getConstraintsForConstructor(Class<?>... parameterTypes) {
        return null;
    }

    @Override
    public Set<ConstructorDescriptor> getConstrainedConstructors() {
        return Collections.emptySet();
    }

    @Override
    public boolean hasConstraints() {
        return beanAnnotationMetadata.hasStereotype(Constraint.class);
    }

    @Override
    public Class<?> getElementClass() {
        return beanIntrospection.getBeanType();
    }

    @Override
    public ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
        return this;
    }

    @Override
    public ConstraintFinder lookingAt(Scope scope) {
        return this;
    }

    @Override
    public ConstraintFinder declaredOn(ElementType... types) {
        return this;
    }

    @Override
    public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
        return constraintDescriptors(beanAnnotationMetadata);
    }

    @Override
    public ConstraintFinder findConstraints() {
        return this;
    }

    /**
     * Internal implementation of {@link PropertyDescriptor}.
     */
    private final class IntrospectedPropertyDescriptor implements PropertyDescriptor, ConstraintFinder {

        private final BeanProperty<?, ?> beanProperty;
        private final AnnotationMetadata annotationMetadata;

        IntrospectedPropertyDescriptor(BeanProperty<?, ?> beanProperty) {
            this.beanProperty = beanProperty;
            this.annotationMetadata = propertyAnnotationMetadata.getOrDefault(beanProperty.getName(), beanProperty);
        }

        @Override
        public String getPropertyName() {
            return beanProperty.getName();
        }

        @Override
        public boolean isCascaded() {
            return annotationMetadata.hasAnnotation(Valid.class);
        }

        @Override
        public Set<GroupConversionDescriptor> getGroupConversions() {
            return Collections.emptySet();
        }

        @Override
        public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
            return Collections.emptySet();
        }

        @Override
        public boolean hasConstraints() {
            return annotationMetadata.hasStereotype(Constraint.class);
        }

        @Override
        public Class<?> getElementClass() {
            return beanProperty.getType();
        }

        @Override
        public ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return this;
        }

        @Override
        public ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return constraintDescriptors(annotationMetadata);
        }

        @Override
        public ConstraintFinder findConstraints() {
            return this;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Set<ConstraintDescriptor<?>> constraintDescriptors(AnnotationMetadata annotationMetadata) {
        return annotationMetadata.getAnnotationTypesByStereotype(Constraint.class, currentClassLoader())
            .stream()
            .flatMap(type -> annotationMetadata.getAnnotationValuesByType(type)
                .stream()
                .map(annotationValue -> (DefaultConstraintDescriptor<?>) new DefaultConstraintDescriptor(
                    type,
                    annotationValue,
                    annotationMetadata
                )))
            .collect(Collectors.toSet());
    }

    private static ClassLoader currentClassLoader() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        return classLoader == null ? IntrospectedBeanDescriptor.class.getClassLoader() : classLoader;
    }
}
