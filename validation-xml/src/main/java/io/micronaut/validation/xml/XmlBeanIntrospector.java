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
package io.micronaut.validation.xml;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationMetadataDelegate;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospectionReference;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.validation.annotation.ValidatedElement;
import io.micronaut.validation.validator.ExecutableHierarchy;
import io.micronaut.validation.validator.ReflectionSupport;
import io.micronaut.validation.validator.metadata.BeanIntrospectorDecorator;
import io.micronaut.validation.validator.metadata.ConfiguredMetadata;
import io.micronaut.validation.validator.metadata.ContainerMappings;
import io.micronaut.validation.validator.metadata.ValidationDeclaration;
import io.micronaut.validation.validator.metadata.ValidationMetadataSupport;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static io.micronaut.validation.xml.XmlMapping.BeanMapping;
import static io.micronaut.validation.xml.XmlMapping.ContainerElementMapping;
import static io.micronaut.validation.xml.XmlMapping.ExecutableKey;
import static io.micronaut.validation.xml.XmlMapping.ExecutableMapping;
import static io.micronaut.validation.xml.XmlMapping.ParameterMapping;
import static io.micronaut.validation.xml.XmlMappingSupport.simpleName;

/**
 * The introspector of a validator configured by XML constraint mappings: it decorates the introspector the
 * validator would read, and describes a type a mapping names by the introspection of that type with the
 * annotation metadata the mapping declares merged into it, or replacing it where the mapping ignores the
 * annotations. A type no mapping names is described by the decorated introspector alone.
 *
 * <p>The validator reads nothing but the introspection, so a constraint declared in XML and one declared by
 * an annotation reach it the same way.</p>
 *
 * @since 5.3.0
 */
@Internal
public final class XmlBeanIntrospector implements BeanIntrospectorDecorator {

    /** The annotations describing the structure of a type, kept where a mapping ignores its annotations. */
    private static final List<String> STRUCTURAL_ANNOTATIONS = List.of(
        ValidationMetadataSupport.HIERARCHY, ContainerMappings.class.getName());

    private final BeanIntrospector delegate;
    private final Map<Class<?>, BeanMapping> beanMappings;
    private final Map<Class<?>, BeanIntrospection<?>> introspections = new ConcurrentHashMap<>();

    XmlBeanIntrospector(BeanIntrospector delegate, Map<Class<?>, BeanMapping> beanMappings) {
        this.delegate = delegate;
        this.beanMappings = Map.copyOf(beanMappings);
    }

    /**
     * Parses constraint mappings and decorates the introspector they configure.
     *
     * @param reflectionSupport The metadata access of the validator, reading the introspector to decorate
     * @param mappingStreams    The mapping streams, closed after parsing
     * @return The introspector describing the mapped types as configured
     */
    public static XmlBeanIntrospector of(ReflectionSupport reflectionSupport, Set<InputStream> mappingStreams) {
        return new XmlBeanIntrospector(reflectionSupport.introspector(), new XmlMappingParser(reflectionSupport, mappingStreams).beanMappings);
    }

    @Override
    public BeanIntrospector getDecorated() {
        return delegate;
    }

    @Override
    public Collection<BeanIntrospection<Object>> findIntrospections(Predicate<? super BeanIntrospectionReference<?>> filter) {
        return delegate.findIntrospections(filter);
    }

    @Override
    public Collection<Class<?>> findIntrospectedTypes(Predicate<? super BeanIntrospectionReference<?>> filter) {
        return delegate.findIntrospectedTypes(filter);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<BeanIntrospection<T>> findIntrospection(Class<T> beanType) {
        BeanMapping mapping = beanMappings.get(beanType);
        if (mapping == null) {
            return delegate.findIntrospection(beanType);
        }
        return Optional.of((BeanIntrospection<T>) introspections.computeIfAbsent(beanType, type -> configured(beanType, mapping)));
    }

    private <T> BeanIntrospection<T> configured(Class<T> beanType, BeanMapping mapping) {
        BeanIntrospection<T> original = delegate.findIntrospection(beanType).orElse(null);
        Map<String, ValidationDeclaration> properties = new LinkedHashMap<>();
        Map<ExecutableKey, ValidationDeclaration> methods = new LinkedHashMap<>();
        Map<ExecutableKey, ValidationDeclaration> constructors = new LinkedHashMap<>();
        boolean ignored = mapping.beanAnnotationsIgnored();
        if (original != null) {
            for (var property : original.getBeanProperties()) {
                if (mapping.properties().containsKey(property.getName())) {
                    continue;
                }
                if (!ignored) {
                    properties.put(property.getName(), new ValidationDeclaration(beanType, property.getName(), property.asArgument(),
                        property.asArgument().getAnnotationMetadata(), List.of(), bean -> property.get(beanType.cast(bean))));
                }
            }
            for (var method : original.getBeanMethods()) {
                var signature = List.of(Argument.toClassArray(method.getArguments()));
                methods.put(new ExecutableKey(method.getName(), signature), new ValidationDeclaration(beanType, method.getName(),
                    unconfigured(method.getReturnType().asArgument(), ignored),
                    ignored ? AnnotationMetadata.EMPTY_METADATA : method.getAnnotationMetadata(),
                    Arrays.stream(method.getArguments()).<Argument<?>>map(argument -> unconfigured(argument, ignored)).toList(), null));
            }
            for (var constructor : original.getConstructors()) {
                var signature = List.of(Argument.toClassArray(constructor.getArguments()));
                AnnotationMetadata metadata = ignored ? AnnotationMetadata.EMPTY_METADATA : constructor.getAnnotationMetadata();
                constructors.put(new ExecutableKey(simpleName(beanType), signature), new ValidationDeclaration(beanType, "<init>",
                    Argument.of(beanType, metadata), metadata,
                    Arrays.stream(constructor.getArguments()).<Argument<?>>map(argument -> unconfigured(argument, ignored)).toList(), null));
            }
        }
        mapping.properties().forEach((name, property) -> {
            var source = property.source();
            var argument = configured(source.argument(), property.metadata(), property.annotationsIgnored(), property.containerElements());
            properties.put(name, new ValidationDeclaration(source.declaringType(), source.name(), argument,
                argument.getAnnotationMetadata(), List.of(), source.reader()));
        });
        mapping.methods().forEach((key, executable) -> methods.put(key, configuredDeclaration(executable)));
        mapping.constructors().forEach((key, executable) -> constructors.put(key, configuredDeclaration(executable)));
        AnnotationMetadata metadata;
        if (original == null) {
            metadata = mapping.classMetadata();
        } else if (mapping.classAnnotationsIgnored()) {
            metadata = ConfiguredMetadata.merge(structural(original.getAnnotationMetadata()), mapping.classMetadata());
        } else {
            metadata = ConfiguredMetadata.merge(original.getAnnotationMetadata(), mapping.classMetadata());
        }
        return new XmlBeanIntrospection<>(beanType, metadata, properties, methods.values(), constructors.values(), original);
    }

    /** What describes the structure of a type rather than constrains it, which ignoring its annotations keeps. */
    private static AnnotationMetadata structural(AnnotationMetadata original) {
        var kept = new MutableAnnotationMetadata();
        for (String name : STRUCTURAL_ANNOTATIONS) {
            AnnotationValue<?> value = original.getAnnotation(name);
            if (value != null) {
                kept.addDeclaredAnnotation(name, value.getValues());
            }
        }
        return kept;
    }

    /** An element no mapping names: as declared, or stripped of its annotations where the bean ignores them. */
    private static Argument<?> unconfigured(Argument<?> argument, boolean annotationsIgnored) {
        return annotationsIgnored ? configured(argument, AnnotationMetadata.EMPTY_METADATA, true, List.of()) : argument;
    }

    private static ValidationDeclaration configuredDeclaration(ExecutableMapping executable) {
        var source = executable.resolvedSource();
        var parameters = configuredParameters(executable, source.parameters().toArray(Argument.ZERO_ARGUMENTS));
        var metadata = configuredExecutableMetadata(executable, source.metadata());
        var argument = configured(source.argument(), executable.returnValue().metadata(), executable.returnValue().annotationsIgnored(), executable.returnValue().containerElements());
        return new ValidationDeclaration(source.declaringType(), source.name(), argument, metadata, List.of(parameters), source.reader());
    }

    private static Argument<?>[] configuredParameters(ExecutableMapping executable, Argument<?>[] arguments) {
        Argument<?>[] configured = new Argument[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            ParameterMapping parameter = i < executable.parameters().size() ? executable.parameters().get(i) : null;
            configured[i] = parameter == null
                ? arguments[i]
                : configured(arguments[i], parameter.metadata(), parameter.annotationsIgnored(), parameter.containerElements());
        }
        return configured;
    }

    /**
     * The cross-parameter and return value constraints of an executable share its annotations. What the
     * executable declares and what the mapping adds are one declaration: the levels they merge from are not
     * the levels of a type hierarchy, so the merged metadata is handed over as a single one.
     */
    private static AnnotationMetadata configuredExecutableMetadata(ExecutableMapping executable, AnnotationMetadata annotationMetadata) {
        boolean ignored = executable.crossParameter().annotationsIgnored() && executable.returnValue().annotationsIgnored();
        AnnotationMetadata merged = ConfiguredMetadata.merge(List.of(
            ignored ? AnnotationMetadata.EMPTY_METADATA : annotationMetadata,
            executable.crossParameter().metadata(),
            executable.returnValue().metadata()
        ));
        return merged instanceof AnnotationMetadataHierarchy ? new ConfiguredDeclaration(merged) : merged;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Argument<?> configured(Argument<?> argument,
                                          AnnotationMetadata metadata,
                                          boolean annotationsIgnored,
                                          List<ContainerElementMapping> containerElements) {
        AnnotationMetadata merged = annotationsIgnored ? metadata : ConfiguredMetadata.merge(argument.getAnnotationMetadata(), metadata);
        Argument<?> configured = argument.withAnnotationMetadata(merged);
        return withContainerElements(configured, annotationsIgnored, containerElements);
    }

    /**
     * The type arguments as configured: the container element constraints merge into the
     * annotations of the type argument they are declared for, or replace them when the annotations
     * are ignored, which then strips the annotations of the type arguments left unconfigured.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Argument<?> withContainerElements(Argument<?> argument,
                                                     boolean annotationsIgnored,
                                                     List<ContainerElementMapping> containerElements) {
        Argument<?>[] typeParameters = argument.getTypeParameters();
        if (typeParameters.length == 0 || (containerElements.isEmpty() && !annotationsIgnored)) {
            return argument;
        }
        Argument<?>[] configured = new Argument[typeParameters.length];
        for (int i = 0; i < typeParameters.length; i++) {
            Argument<?> typeParameter = typeParameters[i];
            ContainerElementMapping mapping = null;
            for (ContainerElementMapping candidate : containerElements) {
                if (candidate.typeArgumentIndex() == i) {
                    mapping = candidate;
                    break;
                }
            }
            if (mapping != null) {
                configured[i] = configured(typeParameter, mapping.metadata(), annotationsIgnored, mapping.containerElements());
            } else if (annotationsIgnored) {
                configured[i] = withContainerElements(
                                typeParameter.withAnnotationMetadata(
                                        AnnotationMetadata.EMPTY_METADATA),
                    true,
                    List.of()
                );
            } else {
                configured[i] = typeParameter;
            }
        }
        AnnotationMetadata metadata = argument.getAnnotationMetadata();
        if (Arrays.stream(configured).anyMatch(parameter -> parameter.getAnnotationMetadata().hasAnnotation(ValidatedElement.class))) {
            var marker = new MutableAnnotationMetadata();
            marker.addDeclaredAnnotation(ValidatedElement.class.getName(), Map.of());
            metadata = ConfiguredMetadata.merge(metadata, marker);
        }
        return ExecutableHierarchy.copyArgument(argument, metadata, configured);
    }

    /**
     * The annotations of a declaration as a mapping configures it.
     *
     * @param getAnnotationMetadata The merged annotations
     */
    private record ConfiguredDeclaration(AnnotationMetadata getAnnotationMetadata) implements AnnotationMetadataDelegate {
    }
}
