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
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.validation.validator.ExecutableHierarchy;
import io.micronaut.validation.validator.ReflectionSupport;
import io.micronaut.validation.validator.IntrospectedBeanDescriptor;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.validation.annotation.ValidatedElement;
import io.micronaut.validation.validator.metadata.ConfiguredMetadata;
import io.micronaut.validation.validator.metadata.ValidationDeclaration;
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.metadata.BeanDescriptor;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.micronaut.validation.xml.XmlMapping.BeanMapping;
import static io.micronaut.validation.xml.XmlMapping.ConstraintDefinition;
import static io.micronaut.validation.xml.XmlMapping.ContainerElementMapping;
import static io.micronaut.validation.xml.XmlMapping.ElementMapping;
import static io.micronaut.validation.xml.XmlMapping.ExecutableKey;
import static io.micronaut.validation.xml.XmlMapping.ExecutableMapping;
import static io.micronaut.validation.xml.XmlMapping.ParameterMapping;
import static io.micronaut.validation.xml.XmlMapping.PropertyMapping;
import static io.micronaut.validation.xml.XmlMappingSupport.simpleName;

/**
 * Internal metadata provider that overlays Jakarta Validation constraint mapping XML on top of
 * Micronaut's generated validation metadata.
 *
 * @since 5.1
 */
@Internal
public final class XmlValidationMetadataProvider implements ValidationMetadataProvider {

    private final ClassLoader classLoader;
    private final Map<Class<?>, BeanMapping> beanMappings;
    private final Map<String, ConstraintDefinition> constraintDefinitions;

    /**
     * Creates a metadata adapter from validated XML mappings.
     * @param classLoader The application class loader
     * @param mappingStreams Mapping streams, closed after parsing
     */
    public XmlValidationMetadataProvider(ClassLoader classLoader, Set<InputStream> mappingStreams) {
        this.classLoader = classLoader;
        var parser = new XmlMappingParser(classLoader, mappingStreams);
        beanMappings = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parser.beanMappings));
        constraintDefinitions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parser.constraintDefinitions));
    }

    @Override
    public Optional<BeanDescriptor> getConstraintsForClass(Class<?> beanType) {
        return getBeanIntrospection(beanType).map(introspection ->
            new IntrospectedBeanDescriptor(introspection, introspection.getAnnotationMetadata(), Map.of(), List.of()));
    }

    @Override
    public <T> Optional<BeanIntrospection<T>> getBeanIntrospection(Class<T> beanType) {
        BeanMapping mapping = beanMappings.get(beanType);
        if (mapping == null) {
            return Optional.empty();
        }
        var original = ReflectionSupport.get().supplemented(BeanIntrospector.forClassLoader(classLoader))
            .findIntrospection(beanType).orElse(null);
        Map<String, ValidationDeclaration> properties = new LinkedHashMap<>();
        Map<ExecutableKey, ValidationDeclaration> methods = new LinkedHashMap<>();
        Map<ExecutableKey, ValidationDeclaration> constructors = new LinkedHashMap<>();
        if (original != null) {
            for (var property : original.getBeanProperties()) {
                boolean ignored = isPropertyAnnotationMetadataIgnored(beanType, property.getName());
                if (ignored && !mapping.properties().containsKey(property.getName())) {
                    continue;
                }
                var argument = configured(property.asArgument(), getPropertyAnnotationMetadata(beanType, property.getName()), ignored, List.of());
                properties.put(property.getName(), new ValidationDeclaration(beanType, property.getName(), argument,
                    argument.getAnnotationMetadata(), List.of(), bean -> property.get(beanType.cast(bean))));
            }
            for (var method : original.getBeanMethods()) {
                var parameters = getMethodParameterArguments(beanType, method.getName(), method.getArguments());
                var signature = Argument.toClassArray(parameters);
                var metadata = getMethodAnnotationMetadata(beanType, method.getName(), signature, method.getAnnotationMetadata());
                var argument = getMethodReturnArgument(beanType, method.getName(), signature, method.getReturnType().asArgument());
                methods.put(new ExecutableKey(method.getName(), List.of(signature)), new ValidationDeclaration(beanType,
                    method.getName(), argument, metadata, List.of(parameters), null));
            }
            for (var constructor : original.getConstructors()) {
                var parameters = getConstructorParameterArguments(beanType, constructor.getArguments());
                var signature = Argument.toClassArray(parameters);
                var metadata = getConstructorAnnotationMetadata(beanType, signature, constructor.getAnnotationMetadata());
                constructors.put(new ExecutableKey("<init>", List.of(signature)), new ValidationDeclaration(beanType,
                    "<init>", Argument.of(beanType, metadata), metadata, List.of(parameters), null));
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
        AnnotationMetadata metadata = mapping.classAnnotationsIgnored() || original == null ? mapping.classMetadata()
            : ConfiguredMetadata.merge(original.getAnnotationMetadata(), mapping.classMetadata());
        return Optional.of(new XmlBeanIntrospection<>(beanType, metadata, properties, methods.values(), constructors.values(), original));
    }

    private static ValidationDeclaration configuredDeclaration(ExecutableMapping executable) {
        var source = executable.resolvedSource();
        var parameters = configuredParameters(executable, source.parameters().toArray(Argument.ZERO_ARGUMENTS));
        var metadata = configuredExecutableMetadata(executable, source.metadata());
        var argument = configured(source.argument(), executable.returnValue().metadata(), executable.returnValue().annotationsIgnored(), executable.returnValue().containerElements());
        return new ValidationDeclaration(source.declaringType(), source.name(), argument, metadata, List.of(parameters), source.reader());
    }

    @Override
    public AnnotationMetadata getBeanAnnotationMetadata(Class<?> beanType) {
        BeanMapping mapping = beanMappings.get(beanType);
        return mapping == null ? AnnotationMetadata.EMPTY_METADATA : mapping.classMetadata();
    }

    @Override
    public boolean isBeanAnnotationMetadataIgnored(Class<?> beanType) {
        BeanMapping mapping = beanMappings.get(beanType);
        return mapping != null && mapping.classAnnotationsIgnored();
    }

    @Override
    public AnnotationMetadata getPropertyAnnotationMetadata(Class<?> beanType, String propertyName) {
        BeanMapping mapping = beanMappings.get(beanType);
        if (mapping == null) {
            return AnnotationMetadata.EMPTY_METADATA;
        }
        PropertyMapping propertyMapping = mapping.properties().get(propertyName);
        return propertyMapping == null ? AnnotationMetadata.EMPTY_METADATA : propertyMapping.metadata();
    }

    @Override
    public boolean isPropertyAnnotationMetadataIgnored(Class<?> beanType, String propertyName) {
        BeanMapping mapping = beanMappings.get(beanType);
        if (mapping == null) {
            return false;
        }
        PropertyMapping propertyMapping = mapping.properties().get(propertyName);
        return propertyMapping == null ? mapping.beanAnnotationsIgnored() : propertyMapping.annotationsIgnored();
    }

    @Override
    public boolean isMethodParameterAnnotationMetadataIgnored(Class<?> beanType,
                                                             String methodName,
                                                             Class<?>[] parameterTypes,
                                                             int parameterIndex) {
        ExecutableMapping method = methodMapping(beanType, methodName, parameterTypes);
        return method == null ? unconfiguredExecutableIgnored(beanType)
            : parameterIndex < method.parameters().size() && method.parameters().get(parameterIndex).annotationsIgnored();
    }

    @Override
    public boolean isMethodReturnValueAnnotationMetadataIgnored(Class<?> beanType,
                                                               String methodName,
                                                               Class<?>[] parameterTypes) {
        ExecutableMapping method = methodMapping(beanType, methodName, parameterTypes);
        return method == null ? unconfiguredExecutableIgnored(beanType) : method.returnValue().annotationsIgnored();
    }

    @Override
    public Argument<?> getPropertyArgument(Class<?> beanType, String propertyName, Argument<?> argument) {
        BeanMapping mapping = beanMappings.get(beanType);
        PropertyMapping property = mapping == null ? null : mapping.properties().get(propertyName);
        if (property == null) {
            return argument;
        }
        return withContainerElements(argument, property.annotationsIgnored(), property.containerElements());
    }

    @Override
    public Argument<?>[] getMethodParameterArguments(Class<?> beanType, String methodName, Argument<?>[] arguments) {
        ExecutableMapping method = methodMapping(beanType, methodName, Argument.toClassArray(arguments));
        return method == null ? unconfiguredParameters(beanType, arguments) : configuredParameters(method, arguments);
    }

    @Override
    public AnnotationMetadata getMethodAnnotationMetadata(Class<?> beanType, String methodName, Class<?>[] parameterTypes, AnnotationMetadata annotationMetadata) {
        ExecutableMapping method = methodMapping(beanType, methodName, parameterTypes);
        return method == null ? (unconfiguredExecutableIgnored(beanType) ? AnnotationMetadata.EMPTY_METADATA : annotationMetadata)
            : configuredExecutableMetadata(method, annotationMetadata);
    }

    @Override
    public Argument<?> getMethodReturnArgument(Class<?> beanType, String methodName, Class<?>[] parameterTypes, Argument<?> argument) {
        ExecutableMapping method = methodMapping(beanType, methodName, parameterTypes);
        return method == null ? unconfiguredElement(beanType, argument) : configuredElement(argument, method.returnValue());
    }

    @Override
    public Argument<?>[] getConstructorParameterArguments(Class<?> beanType, Argument<?>[] arguments) {
        ExecutableMapping constructor = constructorMapping(beanType, Argument.toClassArray(arguments));
        return constructor == null ? unconfiguredParameters(beanType, arguments) : configuredParameters(constructor, arguments);
    }

    @Override
    public AnnotationMetadata getConstructorAnnotationMetadata(Class<?> beanType, Class<?>[] parameterTypes, AnnotationMetadata annotationMetadata) {
        ExecutableMapping constructor = constructorMapping(beanType, parameterTypes);
        return constructor == null ? (unconfiguredExecutableIgnored(beanType) ? AnnotationMetadata.EMPTY_METADATA : annotationMetadata)
            : configuredExecutableMetadata(constructor, annotationMetadata);
    }

    @Override
    public Argument<?> getConstructorReturnArgument(Class<?> beanType, Class<?>[] parameterTypes, Argument<?> argument) {
        ExecutableMapping constructor = constructorMapping(beanType, parameterTypes);
        return constructor == null ? unconfiguredElement(beanType, argument) : configuredElement(argument, constructor.returnValue());
    }

    private boolean unconfiguredExecutableIgnored(Class<?> beanType) {
        BeanMapping mapping = beanMappings.get(beanType);
        return mapping != null && mapping.beanAnnotationsIgnored();
    }

    private Argument<?> unconfiguredElement(Class<?> beanType, Argument<?> argument) {
        return unconfiguredExecutableIgnored(beanType)
            ? configured(argument, AnnotationMetadata.EMPTY_METADATA, true, List.of()) : argument;
    }

    private Argument<?>[] unconfiguredParameters(Class<?> beanType, Argument<?>[] arguments) {
        return unconfiguredExecutableIgnored(beanType)
            ? Arrays.stream(arguments).map(argument -> unconfiguredElement(beanType, argument)).toArray(Argument<?>[]::new) : arguments;
    }

    private @Nullable ExecutableMapping constructorMapping(Class<?> beanType, Class<?>[] parameterTypes) {
        BeanMapping mapping = beanMappings.get(beanType);
        return mapping == null ? null : mapping.constructors().get(new ExecutableKey(simpleName(beanType), List.of(parameterTypes)));
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

    /** The cross-parameter and return value constraints of an executable share its annotations. */
    private static AnnotationMetadata configuredExecutableMetadata(ExecutableMapping executable, AnnotationMetadata annotationMetadata) {
        boolean ignored = executable.crossParameter().annotationsIgnored() && executable.returnValue().annotationsIgnored();
        return ConfiguredMetadata.merge(List.of(
            ignored ? AnnotationMetadata.EMPTY_METADATA : annotationMetadata,
            executable.crossParameter().metadata(),
            executable.returnValue().metadata()
        ));
    }

    private static Argument<?> configuredElement(Argument<?> argument, ElementMapping element) {
        return configured(argument, element.metadata(), element.annotationsIgnored(), element.containerElements());
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

    private @Nullable ExecutableMapping methodMapping(Class<?> beanType, String methodName, Class<?>[] parameterTypes) {
        BeanMapping mapping = beanMappings.get(beanType);
        if (mapping == null) {
            return null;
        }
        return mapping.methods().get(new ExecutableKey(methodName, List.of(parameterTypes)));
    }

    @Override
    public <A extends Annotation> Optional<List<Class<? extends ConstraintValidator<A, ?>>>> getConstraintValidatorClasses(
        Class<A> constraintType,
        List<Class<? extends ConstraintValidator<A, ?>>> existingValidatorClasses) {
        ConstraintDefinition constraintDefinition = constraintDefinitions.get(constraintType.getName());
        if (constraintDefinition == null) {
            return Optional.empty();
        }
        List<Class<? extends ConstraintValidator<A, ?>>> validatorClasses = new ArrayList<>();
        if (constraintDefinition.includeExistingValidators()) {
            validatorClasses.addAll(existingValidatorClasses);
            if (validatorClasses.isEmpty()) {
                validatorClasses.addAll(
                        (List) ReflectionSupport.get().declaredValidators(constraintType));
            }
        }
        validatorClasses.addAll((List) constraintDefinition.validatorClasses());
        return Optional.of(List.copyOf(validatorClasses));
    }

}
