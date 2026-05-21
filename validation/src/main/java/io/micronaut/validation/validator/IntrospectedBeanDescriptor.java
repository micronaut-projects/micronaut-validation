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
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.Valid;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final List<ValidationMetadataProvider> metadataProviders;

    /**
     * Default constructor.
     *
     * @param beanIntrospection The bean introspection
     */
    IntrospectedBeanDescriptor(BeanIntrospection<?> beanIntrospection) {
        this(beanIntrospection, beanIntrospection.getAnnotationMetadata(), Collections.emptyMap(), List.of());
    }

    /**
     * @param beanIntrospection The bean introspection
     * @param beanAnnotationMetadata The bean annotation metadata
     * @param propertyAnnotationMetadata The property annotation metadata
     */
    IntrospectedBeanDescriptor(BeanIntrospection<?> beanIntrospection,
                               AnnotationMetadata beanAnnotationMetadata,
                               Map<String, AnnotationMetadata> propertyAnnotationMetadata) {
        this(beanIntrospection, beanAnnotationMetadata, propertyAnnotationMetadata, List.of());
    }

    /**
     * @param beanIntrospection The bean introspection
     * @param beanAnnotationMetadata The bean annotation metadata
     * @param propertyAnnotationMetadata The property annotation metadata
     * @param metadataProviders The validation metadata providers
     */
    IntrospectedBeanDescriptor(BeanIntrospection<?> beanIntrospection,
                               AnnotationMetadata beanAnnotationMetadata,
                               Map<String, AnnotationMetadata> propertyAnnotationMetadata,
                               List<ValidationMetadataProvider> metadataProviders) {
        ArgumentUtils.requireNonNull("beanIntrospection", beanIntrospection);
        this.beanIntrospection = beanIntrospection;
        this.beanAnnotationMetadata = beanAnnotationMetadata;
        this.propertyAnnotationMetadata = new LinkedHashMap<>(propertyAnnotationMetadata);
        this.metadataProviders = List.copyOf(metadataProviders);
    }

    @Override
    public boolean isBeanConstrained() {
        return hasConstraints() || getConstrainedProperties().stream().anyMatch(property -> property.hasConstraints() || property.isCascaded());
    }

    @Override
    public PropertyDescriptor getConstraintsForProperty(String propertyName) {
        PropertyDescriptor introspectedDescriptor = beanIntrospection.getProperty(propertyName)
            .map(IntrospectedPropertyDescriptor::new)
            .filter(property -> property.hasConstraints() || property.isCascaded() || !property.getConstrainedContainerElementTypes().isEmpty())
            .map(PropertyDescriptor.class::cast)
            .orElse(null);
        PropertyMetadataResolution metadata = propertyMetadata(propertyName);
        if (metadata.annotationsIgnored()) {
            return metadata.descriptor();
        }
        if (introspectedDescriptor == null) {
            return metadata.descriptor();
        }
        if (metadata.descriptor() != null
            && metadata.descriptor().getConstraintDescriptors().size() > introspectedDescriptor.getConstraintDescriptors().size()) {
            return metadata.descriptor();
        }
        return introspectedDescriptor;
    }

    @Override
    public Set<PropertyDescriptor> getConstrainedProperties() {
        Map<String, PropertyDescriptor> properties = beanIntrospection.getBeanProperties()
            .stream()
            .map(IntrospectedPropertyDescriptor::new)
            .filter(property -> property.hasConstraints() || property.isCascaded())
            .collect(Collectors.toMap(PropertyDescriptor::getPropertyName, property -> property, (left, right) -> left, LinkedHashMap::new));
        metadataProviders.stream()
            .flatMap(provider -> provider.getConstraintsForClass(beanIntrospection.getBeanType()).stream())
            .flatMap(descriptor -> descriptor.getConstrainedProperties().stream())
            .forEach(property -> properties.merge(
                property.getPropertyName(),
                property,
                (existing, replacement) -> replacement.getConstraintDescriptors().size() > existing.getConstraintDescriptors().size() ? replacement : existing
            ));
        for (BeanProperty<?, ?> beanProperty : beanIntrospection.getBeanProperties()) {
            String propertyName = beanProperty.getName();
            PropertyMetadataResolution metadata = propertyMetadata(propertyName);
            if (metadata.annotationsIgnored()) {
                if (metadata.descriptor() == null || !isConstrained(metadata.descriptor())) {
                    properties.remove(propertyName);
                } else {
                    properties.put(propertyName, metadata.descriptor());
                }
            }
        }
        return new LinkedHashSet<>(properties.values());
    }

    private PropertyMetadataResolution propertyMetadata(String propertyName) {
        PropertyDescriptor descriptor = null;
        boolean annotationsIgnored = false;
        for (ValidationMetadataProvider metadataProvider : metadataProviders) {
            descriptor = metadataProvider.getConstraintsForClass(beanIntrospection.getBeanType())
                .map(beanDescriptor -> beanDescriptor.getConstraintsForProperty(propertyName))
                .orElse(null);
            if (metadataProvider.isPropertyAnnotationMetadataIgnored(beanIntrospection.getBeanType(), propertyName)) {
                annotationsIgnored = true;
                break;
            }
            if (descriptor != null) {
                break;
            }
        }
        return new PropertyMetadataResolution(descriptor, annotationsIgnored);
    }

    private static boolean isConstrained(PropertyDescriptor property) {
        return property.hasConstraints()
            || property.isCascaded()
            || !property.getGroupConversions().isEmpty()
            || !property.getConstrainedContainerElementTypes().isEmpty();
    }

    @Override
    public MethodDescriptor getConstraintsForMethod(String methodName, Class<?>... parameterTypes) {
        return metadataProviders.stream()
            .flatMap(provider -> provider.getConstraintsForClass(beanIntrospection.getBeanType()).stream())
            .map(descriptor -> descriptor.getConstraintsForMethod(methodName, parameterTypes))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    @Override
    public Set<MethodDescriptor> getConstrainedMethods(MethodType methodType, MethodType... methodTypes) {
        return metadataProviders.stream()
            .flatMap(provider -> provider.getConstraintsForClass(beanIntrospection.getBeanType()).stream())
            .flatMap(descriptor -> descriptor.getConstrainedMethods(methodType, methodTypes).stream())
            .collect(Collectors.toSet());
    }

    @Override
    public ConstructorDescriptor getConstraintsForConstructor(Class<?>... parameterTypes) {
        return metadataProviders.stream()
            .flatMap(provider -> provider.getConstraintsForClass(beanIntrospection.getBeanType()).stream())
            .map(descriptor -> descriptor.getConstraintsForConstructor(parameterTypes))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    @Override
    public Set<ConstructorDescriptor> getConstrainedConstructors() {
        return metadataProviders.stream()
            .flatMap(provider -> provider.getConstraintsForClass(beanIntrospection.getBeanType()).stream())
            .flatMap(descriptor -> descriptor.getConstrainedConstructors().stream())
            .collect(Collectors.toSet());
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
        for (ValidationMetadataProvider metadataProvider : metadataProviders) {
            Optional<BeanDescriptor> descriptor = metadataProvider.getConstraintsForClass(beanIntrospection.getBeanType());
            if (metadataProvider.isBeanAnnotationMetadataIgnored(beanIntrospection.getBeanType()) && descriptor.isPresent()) {
                return descriptor.get().getConstraintDescriptors();
            }
        }
        return constraintDescriptors(beanAnnotationMetadata);
    }

    @Override
    public ConstraintFinder findConstraints() {
        return this;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Set<ConstraintDescriptor<?>> constraintDescriptors(AnnotationMetadata annotationMetadata) {
        Map<String, ConstraintDescriptor<?>> descriptors = new LinkedHashMap<>();
        Set<String> declaredAnnotationNames = annotationMetadata.getDeclaredAnnotationNames();
        List<Class<? extends Annotation>> constraintTypes = annotationMetadata.getAnnotationTypesByStereotype(Constraint.class, currentClassLoader());
        boolean hasDeclaredConstraint = constraintTypes.stream().anyMatch(type -> ConstraintAnnotationKey.isDeclaredConstraint(declaredAnnotationNames, type));
        for (Class<? extends Annotation> type : constraintTypes) {
            if (hasDeclaredConstraint && !ConstraintAnnotationKey.isDeclaredConstraint(declaredAnnotationNames, type)) {
                continue;
            }
            for (AnnotationValue<? extends Annotation> annotationValue : annotationMetadata.getAnnotationValuesByType(type)) {
                descriptors.putIfAbsent(
                    ConstraintAnnotationKey.of(type, annotationValue),
                    constraintDescriptor(type, annotationValue, annotationMetadata)
                );
            }
        }
        return new LinkedHashSet<>(descriptors.values());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DefaultConstraintDescriptor<?> constraintDescriptor(Class<? extends Annotation> type,
                                                               AnnotationValue<? extends Annotation> annotationValue,
                                                               AnnotationMetadata annotationMetadata) {
        Optional<List<Class<? extends ConstraintValidator<Annotation, ?>>>> validatorClasses = constraintValidatorClasses(
            (Class<Annotation>) type,
            (AnnotationValue<Annotation>) annotationValue
        );
        return validatorClasses
            .map(classes -> new DefaultConstraintDescriptor(
                type,
                annotationValue,
                annotationMetadata,
                classes,
                true
            ))
            .orElseGet(() -> new DefaultConstraintDescriptor(
                type,
                annotationValue,
                annotationMetadata
            ));
    }

    private Optional<List<Class<? extends ConstraintValidator<Annotation, ?>>>> constraintValidatorClasses(
        Class<Annotation> constraintType,
        AnnotationValue<Annotation> annotationValue) {
        List<Class<? extends ConstraintValidator<Annotation, ?>>> validatorClasses =
            (List) List.of(annotationValue.classValues(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY));
        Optional<List<Class<? extends ConstraintValidator<Annotation, ?>>>> configuredClasses = Optional.empty();
        for (ValidationMetadataProvider metadataProvider : metadataProviders) {
            Optional<List<Class<? extends ConstraintValidator<Annotation, ?>>>> providerClasses =
                metadataProvider.getConstraintValidatorClasses(constraintType, validatorClasses);
            if (providerClasses.isPresent()) {
                configuredClasses = providerClasses;
                validatorClasses = providerClasses.get();
            }
        }
        return configuredClasses;
    }

    private static ClassLoader currentClassLoader() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        return classLoader == null ? IntrospectedBeanDescriptor.class.getClassLoader() : classLoader;
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
            List<AnnotationValue<ConvertGroup>> conversions = annotationMetadata.getAnnotationValuesByType(ConvertGroup.class);
            if (conversions.isEmpty()) {
                return Collections.emptySet();
            }
            Map<Class<?>, Class<?>> groups = new LinkedHashMap<>();
            for (AnnotationValue<ConvertGroup> conversion : conversions) {
                Class<?> from = conversion.classValue("from").orElse(Default.class);
                Class<?> to = conversion.classValue("to")
                    .orElseThrow(() -> new ConstraintDeclarationException("Group conversion is missing a target group"));
                Class<?> previous = groups.putIfAbsent(from, to);
                if (previous != null) {
                    throw new ConstraintDeclarationException("Multiple group conversions declare the same source group: " + from.getName());
                }
            }
            Set<GroupConversionDescriptor> descriptors = new LinkedHashSet<>();
            groups.forEach((from, to) -> descriptors.add(new DefaultGroupConversionDescriptor(from, to)));
            return descriptors;
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

    private record DefaultGroupConversionDescriptor(Class<?> from, Class<?> to) implements GroupConversionDescriptor {

        @Override
        public Class<?> getFrom() {
            return from;
        }

        @Override
        public Class<?> getTo() {
            return to;
        }
    }

    private record PropertyMetadataResolution(
        PropertyDescriptor descriptor,
        boolean annotationsIgnored
    ) {
    }
}
