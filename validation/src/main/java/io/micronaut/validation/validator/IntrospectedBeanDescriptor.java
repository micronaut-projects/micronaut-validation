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
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.type.Argument;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.beans.BeanConstructor;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.beans.BeanPropertyMember;
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.reflection.MethodHierarchy;
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;
import io.micronaut.reflection.ReflectiveIntrospection;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import jakarta.validation.GroupSequence;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ConstructorDescriptor;
import jakarta.validation.metadata.ParameterDescriptor;
import jakarta.validation.metadata.ExecutableDescriptor;
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
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.Arrays;
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
    private final IntrospectedExecutableDescriptors executables;
    @Nullable
    private final ValidatorDeclarations declarations;

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
        this(beanIntrospection, beanAnnotationMetadata, propertyAnnotationMetadata, metadataProviders, null);
    }

    IntrospectedBeanDescriptor(BeanIntrospection<?> beanIntrospection,
                               AnnotationMetadata beanAnnotationMetadata,
                               Map<String, AnnotationMetadata> propertyAnnotationMetadata,
                               List<ValidationMetadataProvider> metadataProviders,
                               @Nullable ValidatorDeclarations declarations) {
        ArgumentUtils.requireNonNull("beanIntrospection", beanIntrospection);
        this.beanIntrospection = beanIntrospection;
        this.beanAnnotationMetadata = beanAnnotationMetadata;
        this.propertyAnnotationMetadata = new LinkedHashMap<>(propertyAnnotationMetadata);
        this.metadataProviders = List.copyOf(metadataProviders);
        this.executables = new IntrospectedExecutableDescriptors(this::constraintDescriptors, declarations);
        this.declarations = declarations;
    }

    @Override
    public boolean isBeanConstrained() {
        return hasConstraints() || getConstrainedProperties().stream().anyMatch(property -> property.hasConstraints() || property.isCascaded());
    }

    @Override
    public PropertyDescriptor getConstraintsForProperty(String propertyName) {
        if (propertyName == null) {
            throw new IllegalArgumentException("Property name cannot be null");
        }
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

    /**
     * The executables come from the introspection — the {@code @Executable} methods and the constructor of a
     * generated one, every public method and the constructor of a reflective one — and from the metadata
     * providers for what the introspection does not know.
     */
    @Override
    public MethodDescriptor getConstraintsForMethod(String methodName, Class<?>... parameterTypes) {
        if (methodName == null) {
            throw new IllegalArgumentException("Method name cannot be null");
        }
        MethodDescriptor provided = metadataProviders.stream()
            .flatMap(provider -> provider.getConstraintsForClass(beanIntrospection.getBeanType()).stream())
            .map(descriptor -> descriptor.getConstraintsForMethod(methodName, parameterTypes))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        if (provided != null) {
            return provided;
        }
        for (BeanMethod<?, ?> method : beanIntrospection.getBeanMethods()) {
            if (method.getName().equals(methodName) && Arrays.equals(Argument.toClassArray(method.getArguments()), parameterTypes)) {
                MethodDescriptor descriptor = executables.method(method);
                return IntrospectedExecutableDescriptors.isConstrained(descriptor) ? descriptor : null;
            }
        }
        return null;
    }

    @Override
    public Set<MethodDescriptor> getConstrainedMethods(MethodType methodType, MethodType... methodTypes) {
        Set<MethodType> requested = EnumSet.of(methodType, methodTypes);
        Map<String, MethodDescriptor> methods = new LinkedHashMap<>();
        for (BeanMethod<?, ?> method : beanIntrospection.getBeanMethods()) {
            if (!requested.contains(isGetter(method) ? MethodType.GETTER : MethodType.NON_GETTER)) {
                continue;
            }
            MethodDescriptor descriptor = executables.method(method);
            if (IntrospectedExecutableDescriptors.isConstrained(descriptor)) {
                methods.putIfAbsent(signature(descriptor.getName(), method.getArguments()), descriptor);
            }
        }
        metadataProviders.stream()
            .flatMap(provider -> provider.getConstraintsForClass(beanIntrospection.getBeanType()).stream())
            .flatMap(descriptor -> descriptor.getConstrainedMethods(methodType, methodTypes).stream())
            .forEach(descriptor -> methods.put(signature(descriptor), descriptor));
        return new LinkedHashSet<>(methods.values());
    }

    @Override
    public ConstructorDescriptor getConstraintsForConstructor(Class<?>... parameterTypes) {
        ConstructorDescriptor provided = metadataProviders.stream()
            .flatMap(provider -> provider.getConstraintsForClass(beanIntrospection.getBeanType()).stream())
            .map(descriptor -> descriptor.getConstraintsForConstructor(parameterTypes))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        if (provided != null) {
            return provided;
        }
        for (BeanConstructor<?> constructor : constructors()) {
            if (Arrays.equals(Argument.toClassArray(constructor.getArguments()), parameterTypes)) {
                ConstructorDescriptor descriptor = executables.constructor(constructor);
                return IntrospectedExecutableDescriptors.isConstrained(descriptor) ? descriptor : null;
            }
        }
        return null;
    }

    @Override
    public Set<ConstructorDescriptor> getConstrainedConstructors() {
        Map<String, ConstructorDescriptor> constructors = new LinkedHashMap<>();
        for (BeanConstructor<?> constructor : constructors()) {
            ConstructorDescriptor descriptor = executables.constructor(constructor);
            if (IntrospectedExecutableDescriptors.isConstrained(descriptor)) {
                constructors.putIfAbsent(signature(descriptor), descriptor);
            }
        }
        metadataProviders.stream()
            .flatMap(provider -> provider.getConstraintsForClass(beanIntrospection.getBeanType()).stream())
            .flatMap(provided -> provided.getConstrainedConstructors().stream())
            .forEach(provided -> constructors.put(signature(provided), provided));
        return new LinkedHashSet<>(constructors.values());
    }

    /**
     * The constructors the introspection knows: every one of a reflective introspection, and of a generated
     * introspection the ones it describes - all the declared constructors of a type introspected with
     * {@code constructors = true}, else the one it instantiates through.
     */
    @SuppressWarnings("unchecked")
    private List<BeanConstructor<?>> constructors() {
        return (List<BeanConstructor<?>>) (List<?>) beanIntrospection.getConstructors();
    }

    private static boolean isGetter(BeanMethod<?, ?> method) {
        if (method.getArguments().length != 0 || method.getReturnType().getType() == void.class) {
            return false;
        }
        String name = method.getName();
        return (name.startsWith("get") && name.length() > 3)
            || (name.startsWith("is") && name.length() > 2 && (method.getReturnType().getType() == boolean.class || method.getReturnType().getType() == Boolean.class));
    }

    private static String signature(String name, Argument<?>[] arguments) {
        StringBuilder signature = new StringBuilder(name).append('(');
        for (Argument<?> argument : arguments) {
            signature.append(argument.getType().getName()).append(',');
        }
        return signature.append(')').toString();
    }

    private static String signature(ExecutableDescriptor descriptor) {
        StringBuilder signature = new StringBuilder(descriptor instanceof MethodDescriptor ? descriptor.getName() : "").append('(');
        for (ParameterDescriptor parameter : descriptor.getParameterDescriptors()) {
            signature.append(parameter.getElementClass().getName()).append(',');
        }
        return signature.append(')').toString();
    }

    @Override
    public boolean hasConstraints() {
        return ConstraintContainers.hasConstraints(beanAnnotationMetadata, currentClassLoader());
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

    private Set<ConstraintDescriptor<?>> constraintDescriptors(AnnotationMetadata annotationMetadata) {
        return new LinkedHashSet<>(constraintDescriptorsByKey(annotationMetadata).values());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, ConstraintDescriptor<?>> constraintDescriptorsByKey(AnnotationMetadata annotationMetadata) {
        Map<String, ConstraintDescriptor<?>> descriptors = new LinkedHashMap<>();
        Set<String> declaredAnnotationNames = annotationMetadata.getDeclaredAnnotationNames();
        List<Class<? extends Annotation>> constraintTypes = constraintTypes(annotationMetadata);
        boolean hasDeclaredConstraint = constraintTypes.stream().anyMatch(type -> ConstraintAnnotationKey.isDeclaredConstraint(declaredAnnotationNames, type));
        for (Class<? extends Annotation> type : constraintTypes) {
            if (hasDeclaredConstraint && !ConstraintAnnotationKey.isDeclaredConstraint(declaredAnnotationNames, type)) {
                continue;
            }
            for (AnnotationValue<? extends Annotation> annotationValue : ConstraintContainers.values(annotationMetadata, type)) {
                descriptors.putIfAbsent(
                    ConstraintAnnotationKey.of(type, annotationValue),
                    constraintDescriptor(type, annotationValue, annotationMetadata)
                );
            }
        }
        return descriptors;
    }

    /**
     * The constraint types of a metadata, loaded by the context class loader.
     */
    private static List<Class<? extends Annotation>> constraintTypes(AnnotationMetadata annotationMetadata) {
        return List.copyOf(ConstraintContainers.constraintTypes(annotationMetadata, currentClassLoader()));
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
    /**
     * A property. Its constraints are those of its metadata; when the introspection knows the members of the
     * property — a {@link ReflectiveIntrospection} does — the finder can look at the local element only,
     * at the fields or the methods only, and the constraints of a member are attributed to the member.
     */
    private final class IntrospectedPropertyDescriptor implements PropertyDescriptor, ConstraintFinder {

        private final BeanProperty<?, ?> beanProperty;
        private final AnnotationMetadata annotationMetadata;
        private final Set<Class<?>> groups;
        private final Scope scope;
        private final Set<ElementType> declaredOn;

        IntrospectedPropertyDescriptor(BeanProperty<?, ?> beanProperty) {
            this(beanProperty, Set.of(), Scope.HIERARCHY, Set.of());
        }

        private IntrospectedPropertyDescriptor(BeanProperty<?, ?> beanProperty, Set<Class<?>> groups, Scope scope, Set<ElementType> declaredOn) {
            this.beanProperty = beanProperty;
            this.annotationMetadata = propertyAnnotationMetadata.getOrDefault(beanProperty.getName(), beanProperty);
            this.groups = groups;
            this.scope = scope;
            this.declaredOn = declaredOn;
        }

        @Override
        public String getPropertyName() {
            return beanProperty.getName();
        }

        @Override
        public boolean isCascaded() {
            if (annotationMetadata.hasStereotype(Valid.class)) {
                return true;
            }
            for (BeanProperty<?, ?> superProperty : superProperties()) {
                if (superProperty.getAnnotationMetadata().getDeclaredMetadata().hasStereotype(Valid.class)) {
                    return true;
                }
            }
            for (BeanPropertyMember<?, ?> member : members()) {
                if (member.getAnnotationMetadata().hasStereotype(Valid.class)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Set<GroupConversionDescriptor> getGroupConversions() {
            Set<GroupConversionDescriptor> conversions = new LinkedHashSet<>(IntrospectedExecutableDescriptors.groupConversions(annotationMetadata));
            for (BeanProperty<?, ?> superProperty : superProperties()) {
                conversions.addAll(IntrospectedExecutableDescriptors.groupConversions(superProperty.getAnnotationMetadata().getDeclaredMetadata()));
            }
            for (BeanPropertyMember<?, ?> member : members()) {
                conversions.addAll(IntrospectedExecutableDescriptors.groupConversions(member.getAnnotationMetadata()));
            }
            return conversions;
        }

        /**
         * The container elements of every member: the field and the getters of the type and of its
         * interfaces may each declare a container type of their own — {@code Roles}, {@code Set<@NotBlank String>},
         * {@code Iterable<@NotNull String>} — and each is a container element type of the property.
         */
        @Override
        public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
            List<? extends BeanPropertyMember<?, ?>> members = members();
            if (members.isEmpty()) {
                List<BeanProperty<?, ?>> superProperties = superProperties();
                if (superProperties.isEmpty()) {
                    return executables.containerElements(beanProperty.asArgument());
                }
                // an interface can declare another container than the implementation — Iterable<@NotNull String>
                // where the class returns a Set — and each is a container element type of the property
                Map<Class<?>, List<Argument<?>>> byContainerType = new LinkedHashMap<>();
                byContainerType.computeIfAbsent(beanProperty.getType(), ignored -> new ArrayList<>()).add(beanProperty.asArgument());
                for (BeanProperty<?, ?> superProperty : superProperties) {
                    byContainerType.computeIfAbsent(superProperty.getType(), ignored -> new ArrayList<>()).add(superProperty.asArgument());
                }
                Set<ContainerElementTypeDescriptor> containerElements = new LinkedHashSet<>();
                for (List<Argument<?>> arguments : byContainerType.values()) {
                    containerElements.addAll(executables.containerElements(MethodHierarchy.mergeArgument(arguments)));
                }
                return containerElements;
            }
            // the type arguments of the members merged per container type: a field and a getter can constrain
            // them differently, an interface getter can declare another container than the implementation
            Map<Class<?>, List<Argument<?>>> byContainer = new LinkedHashMap<>();
            for (BeanPropertyMember<?, ?> member : members) {
                byContainer.computeIfAbsent(member.asArgument().getType(), ignored -> new ArrayList<>()).add(member.asArgument());
            }
            Set<ContainerElementTypeDescriptor> descriptors = new LinkedHashSet<>();
            for (List<Argument<?>> arguments : byContainer.values()) {
                descriptors.addAll(executables.containerElements(MethodHierarchy.mergeArgument(arguments)));
            }
            return descriptors;
        }

        @Override
        public boolean hasConstraints() {
            if (ConstraintContainers.hasConstraints(annotationMetadata, currentClassLoader())) {
                return true;
            }
            for (BeanProperty<?, ?> superProperty : superProperties()) {
                if (ConstraintContainers.hasConstraints(superProperty.getAnnotationMetadata().getDeclaredMetadata(), currentClassLoader())) {
                    return true;
                }
            }
            for (BeanPropertyMember<?, ?> member : members()) {
                if (ConstraintContainers.hasConstraints(member.getAnnotationMetadata(), currentClassLoader())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The members of the property, where the introspection tells apart what each declares: a reflective
         * introspection reads the field of the type and the getter of every type of the hierarchy declaring
         * one, so a member is attributed to the type declaring it. The members a generated introspection lists
         * for a type introspected with {@code members = true} are the field and the getter of that type alone,
         * with what the super types declare merged into the getter, so reading them here would lose the
         * declaring types the specification attributes constraints to; the super types are read from their
         * own introspections instead, see {@link #superProperties()}.
         */
        private List<? extends BeanPropertyMember<?, ?>> members() {
            return beanIntrospection instanceof ReflectiveIntrospection<?>
                ? beanProperty.getMembers()
                : List.of();
        }

        /**
         * The property as the super types of the bean declare it, the closest first. A generated
         * introspection merges what a super type declares into the metadata of the property, so the
         * declaring type of a constraint — which the specification needs for {@link Scope#LOCAL_ELEMENT},
         * for the implicit group of an interface and for constraints that add up across a hierarchy — is
         * read back from the introspections of the super types themselves.
         *
         * @return The property of each super type declaring one, empty when the introspection knows its
         * members already or when there is nothing to read the super types from
         */
        private List<BeanProperty<?, ?>> superProperties() {
            if (declarations == null || beanIntrospection instanceof ReflectiveIntrospection<?>) {
                return List.of();
            }
            List<BeanProperty<?, ?>> properties = new ArrayList<>();
            for (BeanIntrospection<?> superIntrospection : declarations.superIntrospections(beanIntrospection)) {
                superIntrospection.getProperty(beanProperty.getName()).ifPresent(properties::add);
            }
            return properties;
        }

        /**
         * The constraints the super types declare on the property, attributed to the type declaring each:
         * an interface puts its constraints in the implicit group of the interface.
         */
        private Map<String, ConstraintDescriptor<?>> superConstraintDescriptors() {
            Map<String, ConstraintDescriptor<?>> descriptors = new LinkedHashMap<>();
            for (BeanProperty<?, ?> superProperty : superProperties()) {
                Class<?> declaringType = superProperty.getDeclaringType();
                AnnotationMetadata declared = superProperty.getAnnotationMetadata().getDeclaredMetadata();
                for (Map.Entry<String, ConstraintDescriptor<?>> entry : constraintDescriptorsByKey(declared).entrySet()) {
                    ConstraintDescriptor<?> descriptor = declaringType.isInterface()
                        ? new ImplicitGroupConstraintDescriptor<>(entry.getValue(), declaringType)
                        : entry.getValue();
                    descriptors.put(entry.getKey(), descriptor);
                }
            }
            return descriptors;
        }

        @Override
        public Class<?> getElementClass() {
            return beanProperty.getType();
        }

        @Override
        public ConstraintFinder unorderedAndMatchingGroups(Class<?>... requested) {
            return new IntrospectedPropertyDescriptor(beanProperty, new LinkedHashSet<>(List.of(requested)), scope, declaredOn);
        }

        @Override
        public ConstraintFinder lookingAt(Scope requested) {
            return new IntrospectedPropertyDescriptor(beanProperty, groups, requested, declaredOn);
        }

        @Override
        public ConstraintFinder declaredOn(ElementType... types) {
            return new IntrospectedPropertyDescriptor(beanProperty, groups, scope, new LinkedHashSet<>(List.of(types)));
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            List<? extends BeanPropertyMember<?, ?>> members = members();
            Set<ConstraintDescriptor<?>> descriptors;
            if (members.isEmpty()) {
                // the members are unknown: the metadata of the property, as validated, with what the super
                // types declare attributed to them rather than to the type inheriting it
                Map<String, ConstraintDescriptor<?>> byKey = new LinkedHashMap<>(constraintDescriptorsByKey(annotationMetadata));
                if (scope != Scope.LOCAL_ELEMENT) {
                    byKey.putAll(superConstraintDescriptors());
                } else {
                    superConstraintDescriptors().keySet().forEach(byKey::remove);
                }
                descriptors = new LinkedHashSet<>(byKey.values());
            } else {
                descriptors = new LinkedHashSet<>();
                for (BeanPropertyMember<?, ?> member : members) {
                    if (scope == Scope.LOCAL_ELEMENT && member.getDeclaringType() != beanIntrospection.getBeanType()) {
                        continue;
                    }
                    if (!declaredOn.isEmpty() && !declaredOn.contains(member.getElementType())) {
                        continue;
                    }
                    for (ConstraintDescriptor<?> descriptor : constraintDescriptors(member.getAnnotationMetadata())) {
                        // a constraint an interface declares is in the group of the interface for the types implementing it
                        boolean implicit = member.getDeclaringType().isInterface() && member.getDeclaringType() != beanIntrospection.getBeanType();
                        descriptors.add(implicit ? new ImplicitGroupConstraintDescriptor<>(descriptor, member.getDeclaringType()) : descriptor);
                    }
                }
            }
            if (groups.isEmpty()) {
                return descriptors;
            }
            Set<Class<?>> effective = effectiveGroups();
            Set<ConstraintDescriptor<?>> matching = new LinkedHashSet<>();
            for (ConstraintDescriptor<?> descriptor : descriptors) {
                if (IntrospectedExecutableDescriptors.matchesGroups(descriptor, effective)) {
                    matching.add(descriptor);
                }
            }
            return matching;
        }

        /**
         * The requested groups, and the groups of the default group sequence of the bean when the default
         * group is among them, as the section 4.4.2 redefines it.
         */
        private Set<Class<?>> effectiveGroups() {
            Set<Class<?>> effective = new LinkedHashSet<>(groups);
            if (groups.contains(Default.class)) {
                for (Class<?> group : beanAnnotationMetadata.classValues(GroupSequence.class)) {
                    if (group != beanIntrospection.getBeanType()) {
                        effective.add(group);
                    }
                }
            }
            return effective;
        }

        @Override
        public ConstraintFinder findConstraints() {
            return new IntrospectedPropertyDescriptor(beanProperty);
        }
    }

    /**
     * A constraint declared by an interface member belongs to the group of the interface as well, as the
     * section 4.4.2 of the specification defines the implicit grouping.
     *
     * @param delegate      The constraint descriptor
     * @param implicitGroup The interface declaring the member
     * @param <A>           The annotation type
     */
    private record ImplicitGroupConstraintDescriptor<A extends Annotation>(ConstraintDescriptor<A> delegate, Class<?> implicitGroup) implements ConstraintDescriptor<A> {

        @Override
        public A getAnnotation() {
            return delegate.getAnnotation();
        }

        @Override
        public String getMessageTemplate() {
            return delegate.getMessageTemplate();
        }

        @Override
        public Set<Class<?>> getGroups() {
            Set<Class<?>> groups = delegate.getGroups();
            if (!groups.isEmpty() && !groups.contains(Default.class)) {
                return groups;
            }
            Set<Class<?>> implicit = new LinkedHashSet<>(groups);
            implicit.add(Default.class);
            implicit.add(implicitGroup);
            return implicit;
        }

        @Override
        public Set<Class<? extends jakarta.validation.Payload>> getPayload() {
            return delegate.getPayload();
        }

        @Override
        public jakarta.validation.ConstraintTarget getValidationAppliesTo() {
            return delegate.getValidationAppliesTo();
        }

        @Override
        public List<Class<? extends ConstraintValidator<A, ?>>> getConstraintValidatorClasses() {
            return delegate.getConstraintValidatorClasses();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }

        @Override
        public Set<ConstraintDescriptor<?>> getComposingConstraints() {
            return delegate.getComposingConstraints();
        }

        @Override
        public boolean isReportAsSingleViolation() {
            return delegate.isReportAsSingleViolation();
        }

        @Override
        public jakarta.validation.metadata.ValidateUnwrappedValue getValueUnwrapping() {
            return delegate.getValueUnwrapping();
        }

        @Override
        public <U> U unwrap(Class<U> type) {
            return delegate.unwrap(type);
        }
    }

    private record PropertyMetadataResolution(
        PropertyDescriptor descriptor,
        boolean annotationsIgnored
    ) {
    }
}
