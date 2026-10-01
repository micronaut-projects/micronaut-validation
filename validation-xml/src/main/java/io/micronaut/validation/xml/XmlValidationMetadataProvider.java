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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.validation.validator.ExecutableHierarchy;
import io.micronaut.validation.validator.ReflectionSupport;
import io.micronaut.validation.validator.ValidationAnnotationUtil;
import io.micronaut.validation.validator.IntrospectedBeanDescriptor;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.validation.annotation.ValidatedElement;
import io.micronaut.validation.validator.metadata.AnnotationMember;
import io.micronaut.validation.validator.metadata.ConfiguredMetadata;
import io.micronaut.validation.validator.metadata.ValidationMetadataSupport;
import io.micronaut.validation.validator.metadata.ValidationDeclaration;
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.GroupSequence;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.BeanDescriptor;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;

/**
 * Internal metadata provider that overlays Jakarta Validation constraint mapping XML on top of
 * Micronaut's generated validation metadata.
 *
 * @since 5.1
 */
@Internal
public final class XmlValidationMetadataProvider implements ValidationMetadataProvider {

    private static final String ELEMENT_ANNOTATION = "annotation";
    private static final String ELEMENT_FIELD = "field";
    private static final String ELEMENT_GETTER = "getter";
    private static final String ELEMENT_VALUE = "value";
    private static final String ATTRIBUTE_IGNORE_ANNOTATIONS = "ignore-annotations";
    private static final String ATTRIBUTE_MESSAGE = "message";
    private static final String ATTRIBUTE_GROUPS = "groups";
    private static final String ATTRIBUTE_PAYLOAD = "payload";
    private static final String ATTRIBUTE_VALIDATION_APPLIES_TO = "validationAppliesTo";
    private static final Set<String> RESERVED_CONSTRAINT_ELEMENT_NAMES = Set.of(ATTRIBUTE_MESSAGE, ATTRIBUTE_GROUPS, ATTRIBUTE_PAYLOAD);
    private static final Set<String> SUPPORTED_MAPPING_VERSIONS = Set.of("1.0", "1.1", "2.0", "3.0", "3.1");
    private static final Set<String> ROOT_ELEMENT_NAMES = Set.of("default-package", "bean", "constraint-definition");

    private final Map<Class<?>, BeanMapping> beanMappings = new LinkedHashMap<>();
    private final Map<String, ConstraintDefinition> constraintDefinitions = new LinkedHashMap<>();
    private final ClassLoader classLoader;

    /**
     * Creates an XML validation metadata provider.
     *
     * @param classLoader The class loader
     * @param mappingStreams The mapping streams
     */
    public XmlValidationMetadataProvider(ClassLoader classLoader, Set<InputStream> mappingStreams) {
        this.classLoader = classLoader;
        RuntimeException failure = null;
        try {
            for (InputStream mappingStream : mappingStreams) {
                parse(mappingStream);
            }
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            ValidationException cleanupFailure = null;
            for (InputStream mappingStream : mappingStreams) {
                try {
                    mappingStream.close();
                } catch (IOException e) {
                    if (cleanupFailure == null) {
                        cleanupFailure =
                                new ValidationException(
                                        "Cannot close constraint mapping stream", e);
                    } else {
                        cleanupFailure.addSuppressed(e);
                    }
                }
            }
            if (cleanupFailure != null) {
                if (failure == null) {
                    throw cleanupFailure;
                }
                failure.addSuppressed(cleanupFailure);
            }
        }
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
                if (ignored && !mapping.properties.containsKey(property.getName())) {
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
        mapping.properties.forEach((name, property) -> {
            var source = property.source();
            var argument = configured(source.argument(), property.metadata(), property.annotationsIgnored(), property.containerElements());
            properties.put(name, new ValidationDeclaration(source.declaringType(), source.name(), argument,
                argument.getAnnotationMetadata(), List.of(), source.reader()));
        });
        mapping.methods.forEach((key, executable) -> methods.put(key, configuredDeclaration(executable)));
        mapping.constructors.forEach((key, executable) -> constructors.put(key, configuredDeclaration(executable)));
        AnnotationMetadata metadata = mapping.classAnnotationsIgnored || original == null ? mapping.classMetadata
            : ConfiguredMetadata.merge(original.getAnnotationMetadata(), mapping.classMetadata);
        return Optional.of(new XmlBeanIntrospection<>(beanType, metadata, properties, methods.values(), constructors.values(), original));
    }

    private static ValidationDeclaration configuredDeclaration(ExecutableMapping executable) {
        var source = executable.resolvedSource();
        var parameters = configuredParameters(executable, source.parameters().toArray(Argument.ZERO_ARGUMENTS));
        var metadata = configuredExecutableMetadata(executable, source.metadata());
        var argument = configured(source.argument(), executable.returnValue.metadata(), executable.returnValue.annotationsIgnored(), executable.returnValue.containerElements());
        return new ValidationDeclaration(source.declaringType(), source.name(), argument, metadata, List.of(parameters), source.reader());
    }

    @Override
    public AnnotationMetadata getBeanAnnotationMetadata(Class<?> beanType) {
        BeanMapping mapping = beanMappings.get(beanType);
        return mapping == null ? AnnotationMetadata.EMPTY_METADATA : mapping.classMetadata;
    }

    @Override
    public boolean isBeanAnnotationMetadataIgnored(Class<?> beanType) {
        BeanMapping mapping = beanMappings.get(beanType);
        return mapping != null && mapping.classAnnotationsIgnored;
    }

    @Override
    public AnnotationMetadata getPropertyAnnotationMetadata(Class<?> beanType, String propertyName) {
        BeanMapping mapping = beanMappings.get(beanType);
        if (mapping == null) {
            return AnnotationMetadata.EMPTY_METADATA;
        }
        PropertyMapping propertyMapping = mapping.properties.get(propertyName);
        return propertyMapping == null ? AnnotationMetadata.EMPTY_METADATA : propertyMapping.metadata;
    }

    @Override
    public boolean isPropertyAnnotationMetadataIgnored(Class<?> beanType, String propertyName) {
        BeanMapping mapping = beanMappings.get(beanType);
        if (mapping == null) {
            return false;
        }
        PropertyMapping propertyMapping = mapping.properties.get(propertyName);
        return propertyMapping == null ? mapping.beanAnnotationsIgnored : propertyMapping.annotationsIgnored;
    }

    @Override
    public boolean isMethodParameterAnnotationMetadataIgnored(Class<?> beanType,
                                                             String methodName,
                                                             Class<?>[] parameterTypes,
                                                             int parameterIndex) {
        ExecutableMapping method = methodMapping(beanType, methodName, parameterTypes);
        return method == null ? unconfiguredExecutableIgnored(beanType)
            : parameterIndex < method.parameters.size() && method.parameters.get(parameterIndex).annotationsIgnored();
    }

    @Override
    public boolean isMethodReturnValueAnnotationMetadataIgnored(Class<?> beanType,
                                                               String methodName,
                                                               Class<?>[] parameterTypes) {
        ExecutableMapping method = methodMapping(beanType, methodName, parameterTypes);
        return method == null ? unconfiguredExecutableIgnored(beanType) : method.returnValue.annotationsIgnored();
    }

    @Override
    public Argument<?> getPropertyArgument(Class<?> beanType, String propertyName, Argument<?> argument) {
        BeanMapping mapping = beanMappings.get(beanType);
        PropertyMapping property = mapping == null ? null : mapping.properties.get(propertyName);
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
        return method == null ? unconfiguredElement(beanType, argument) : configuredElement(argument, method.returnValue);
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
        return constructor == null ? unconfiguredElement(beanType, argument) : configuredElement(argument, constructor.returnValue);
    }

    private boolean unconfiguredExecutableIgnored(Class<?> beanType) {
        BeanMapping mapping = beanMappings.get(beanType);
        return mapping != null && mapping.beanAnnotationsIgnored;
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
        return mapping == null ? null : mapping.constructors.get(new ExecutableKey(simpleName(beanType), List.of(parameterTypes)));
    }

    private static Argument<?>[] configuredParameters(ExecutableMapping executable, Argument<?>[] arguments) {
        Argument<?>[] configured = new Argument[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            ParameterMapping parameter = i < executable.parameters.size() ? executable.parameters.get(i) : null;
            configured[i] = parameter == null
                ? arguments[i]
                : configured(arguments[i], parameter.metadata(), parameter.annotationsIgnored(), parameter.containerElements());
        }
        return configured;
    }

    /** The cross-parameter and return value constraints of an executable share its annotations. */
    private static AnnotationMetadata configuredExecutableMetadata(ExecutableMapping executable, AnnotationMetadata annotationMetadata) {
        boolean ignored = executable.crossParameter.annotationsIgnored() && executable.returnValue.annotationsIgnored();
        return ConfiguredMetadata.merge(List.of(
            ignored ? AnnotationMetadata.EMPTY_METADATA : annotationMetadata,
            executable.crossParameter.metadata(),
            executable.returnValue.metadata()
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
        return mapping.methods.get(new ExecutableKey(methodName, List.of(parameterTypes)));
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

    private void parse(InputStream inputStream) {
        try {
            Document document = SecureXmlDocumentBuilder.parse(inputStream);
            Element root = document.getDocumentElement();
            validateVersion(root, SUPPORTED_MAPPING_VERSIONS, "constraint mapping XML");
            validateRootElements(root, ROOT_ELEMENT_NAMES, "constraint mapping XML");
            String defaultPackage = textOfChild(root, "default-package");
            Map<String, ConstraintDefinition> mappingConstraintDefinitions = constraintDefinitions(root, defaultPackage);
            for (var definition : mappingConstraintDefinitions.entrySet()) {
                if (constraintDefinitions.putIfAbsent(definition.getKey(), definition.getValue())
                        != null) {
                    throw new ValidationException(
                            "Duplicate constraint definition: " + definition.getKey());
                }
            }
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element element && "bean".equals(localName(element))) {
                    parseBean(element, defaultPackage);
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new ValidationException("Cannot parse constraint mapping XML", e);
        }
    }

    private Map<String, ConstraintDefinition> constraintDefinitions(Element root, String defaultPackage) {
        Map<String, ConstraintDefinition> definitions = new LinkedHashMap<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element definition) || !"constraint-definition".equals(localName(definition))) {
                continue;
            }
            String annotationName = resolveClassName(requireAttribute(definition, ELEMENT_ANNOTATION), defaultPackage);
            Element validatedBy = child(definition, "validated-by");
            if (validatedBy == null) {
                continue;
            }
            boolean includeExistingValidators = booleanAttribute(validatedBy, "include-existing-validators", true);
            List<Class<?>> validators = new ArrayList<>();
            NodeList validatorNodes = validatedBy.getChildNodes();
            for (int j = 0; j < validatorNodes.getLength(); j++) {
                Node validatorNode = validatorNodes.item(j);
                if (validatorNode instanceof Element value && ELEMENT_VALUE.equals(localName(value))) {
                    validators.add(loadClass(resolveClassName(text(value), defaultPackage)));
                }
            }
            definitions.put(annotationName, new ConstraintDefinition(List.copyOf(validators), includeExistingValidators));
        }
        return definitions;
    }

    private void parseBean(Element bean, String defaultPackage) {
        Class<?> beanType = loadClass(resolveClassName(requireAttribute(bean, "class"), defaultPackage));
        boolean beanAnnotationsIgnored = booleanAttribute(bean, ATTRIBUTE_IGNORE_ANNOTATIONS, true);
        MutableAnnotationMetadata classMetadata = new MutableAnnotationMetadata();
        boolean classAnnotationsIgnored = beanAnnotationsIgnored;
        Map<String, PropertyMapping> properties = new LinkedHashMap<>();
        Map<ExecutableKey, ExecutableMapping> methods = new LinkedHashMap<>();
        Map<ExecutableKey, ExecutableMapping> constructors = new LinkedHashMap<>();
        Set<String> configuredFields = new LinkedHashSet<>();
        Set<String> configuredGetters = new LinkedHashSet<>();
        Set<String> configuredGetterMethods = new LinkedHashSet<>();
        NodeList children = bean.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            String elementName = localName(element);
            switch (elementName) {
                case "class" -> classAnnotationsIgnored = parseClassMetadata(element, defaultPackage, beanAnnotationsIgnored, classMetadata);
                case ELEMENT_FIELD, ELEMENT_GETTER -> parseProperty(
                    beanType,
                    elementName,
                    element,
                    defaultPackage,
                    beanAnnotationsIgnored,
                    properties,
                    methods,
                    configuredFields,
                    configuredGetters,
                    configuredGetterMethods
                );
                case "constructor" -> parseConstructor(beanType, element, defaultPackage, beanAnnotationsIgnored, constructors);
                case "method" -> parseMethod(beanType, element, defaultPackage, beanAnnotationsIgnored, configuredGetterMethods, methods);
                default -> {
                    // Root element validation rejects unsupported bean children before parsing.
                }
            }
        }
        if (beanMappings.putIfAbsent(beanType, new BeanMapping(classMetadata, beanAnnotationsIgnored, classAnnotationsIgnored, properties, methods, constructors)) != null) {
            throw new ValidationException("Bean configured more than once in validation XML: " + beanType.getName());
        }
    }

    private boolean parseClassMetadata(Element element,
                                       String defaultPackage,
                                       boolean beanAnnotationsIgnored,
                                       MutableAnnotationMetadata classMetadata) {
        parseGroupSequence(element, defaultPackage, classMetadata);
        parseConstraints(element, defaultPackage, classMetadata);
        return booleanAttribute(element, ATTRIBUTE_IGNORE_ANNOTATIONS, beanAnnotationsIgnored);
    }

    private void parseProperty(Class<?> beanType,
                               String elementName,
                               Element element,
                               String defaultPackage,
                               boolean beanAnnotationsIgnored,
                               Map<String, PropertyMapping> properties,
                               Map<ExecutableKey, ExecutableMapping> methods,
                               Set<String> configuredFields,
                               Set<String> configuredGetters,
                               Set<String> configuredGetterMethods) {
        String propertyName = requireAttribute(element, "name");
        ValidationDeclaration source = findPropertySource(beanType, elementName, propertyName);
        if (source == null) {
            throw new ValidationException("Unknown " + elementName + " in validation XML: " + beanType.getName() + "." + propertyName);
        }
        validatePropertyConfiguredOnce(beanType, elementName, propertyName, methods, configuredFields, configuredGetters, configuredGetterMethods);
        MutableAnnotationMetadata propertyMetadata = new MutableAnnotationMetadata();
        parseConstraints(element, defaultPackage, propertyMetadata);
        if (child(element, "valid") != null) {
            propertyMetadata.addDeclaredAnnotation(Valid.class.getName(), Map.of());
        }
        parseGroupConversions(element, defaultPackage, propertyMetadata);
        Argument<?> propertyType = source.argument();
        properties.put(propertyName, new PropertyMapping(
            propertyMetadata,
            booleanAttribute(element, ATTRIBUTE_IGNORE_ANNOTATIONS, beanAnnotationsIgnored),
            source,
                        source.argument().getType(),
            parseContainerElements(element, defaultPackage, propertyType)
        ));
    }

    private static void validatePropertyConfiguredOnce(Class<?> beanType,
                                                       String elementName,
                                                       String propertyName,
                                                       Map<ExecutableKey, ExecutableMapping> methods,
                                                       Set<String> configuredFields,
                                                       Set<String> configuredGetters,
                                                       Set<String> configuredGetterMethods) {
        if (ELEMENT_FIELD.equals(elementName) && !configuredFields.add(propertyName)) {
            throw new ValidationException("Field configured more than once in validation XML: " + beanType.getName() + "." + propertyName);
        }
        if (ELEMENT_GETTER.equals(elementName)) {
            validateGetterConfiguredOnce(beanType, propertyName, methods, configuredGetters, configuredGetterMethods);
        }
    }

    private static void validateGetterConfiguredOnce(Class<?> beanType,
                                                     String propertyName,
                                                     Map<ExecutableKey, ExecutableMapping> methods,
                                                     Set<String> configuredGetters,
                                                     Set<String> configuredGetterMethods) {
        if (!configuredGetters.add(propertyName)) {
            throw new ValidationException("Getter configured more than once in validation XML: " + beanType.getName() + "." + propertyName);
        }
        Set<String> getterMethods = getterMethodNames(beanType, propertyName);
        for (String getterMethod : getterMethods) {
            if (methods.containsKey(new ExecutableKey(getterMethod, List.of()))) {
                throw new ValidationException("Getter configured as both getter and method in validation XML: " + beanType.getName() + "." + getterMethod);
            }
        }
        configuredGetterMethods.addAll(getterMethods);
    }

    private void parseConstructor(Class<?> beanType,
                                  Element element,
                                  String defaultPackage,
                                  boolean beanAnnotationsIgnored,
                                  Map<ExecutableKey, ExecutableMapping> constructors) {
        ExecutableMapping constructor = parseExecutable(
                        simpleName(beanType), element, defaultPackage, beanAnnotationsIgnored);
        ValidationDeclaration source = findConstructor(beanType, constructor.parameterTypes());
        if (source == null) {
            throw new ValidationException("Unknown constructor in validation XML: " + beanType.getName() + constructor.parameterTypes());
        }
        constructor = constructor.withSource(source)
            .withContainerElements(element, defaultPackage, this);
        ExecutableKey key = new ExecutableKey(simpleName(beanType), constructor.parameterTypes());
        if (constructors.putIfAbsent(key, constructor) != null) {
            throw new ValidationException("Constructor configured more than once in validation XML: " + beanType.getName() + constructor.parameterTypes());
        }
    }

    private void parseMethod(Class<?> beanType,
                             Element element,
                             String defaultPackage,
                             boolean beanAnnotationsIgnored,
                             Set<String> configuredGetterMethods,
                             Map<ExecutableKey, ExecutableMapping> methods) {
        String methodName = requireAttribute(element, "name");
        ExecutableMapping method = parseExecutable(methodName, element, defaultPackage, beanAnnotationsIgnored);
        ValidationDeclaration source = findMethod(beanType, methodName, method.parameterTypes());
        if (source == null) {
            throw new ValidationException("Unknown method in validation XML: " + beanType.getName() + "." + methodName + method.parameterTypes());
        }
        method = method.withSource(source)
            .withContainerElements(element, defaultPackage, this);
        if (method.parameterTypes().isEmpty() && configuredGetterMethods.contains(methodName)) {
            throw new ValidationException("Getter configured as both getter and method in validation XML: " + beanType.getName() + "." + methodName);
        }
        ExecutableKey key = new ExecutableKey(methodName, method.parameterTypes());
        if (methods.putIfAbsent(key, method) != null) {
            throw new ValidationException("Method configured more than once in validation XML: " + beanType.getName() + "." + methodName + method.parameterTypes());
        }
    }

    @Nullable
    private static ValidationDeclaration findPropertySource(
            Class<?> beanType, String elementName, String propertyName) {
        return ReflectionSupport.get().declaration(beanType, elementName, propertyName, List.of());
    }

    private static Set<String> getterMethodNames(Class<?> beanType, String propertyName) {
        ValidationDeclaration getter = findPropertySource(beanType, ELEMENT_GETTER, propertyName);
        return getter == null ? Set.of() : Set.of(getter.name());
    }

    @Nullable
    private static ValidationDeclaration findConstructor(
            Class<?> beanType, List<Class<?>> parameters) {
        return ReflectionSupport.get()
                .declaration(beanType, "constructor", simpleName(beanType), parameters);
    }

    @Nullable
    private static ValidationDeclaration findMethod(
            Class<?> beanType, String name, List<Class<?>> parameters) {
        return ReflectionSupport.get().declaration(beanType, "method", name, parameters);
    }

    private static String simpleName(Class<?> type) {
        String name = type.getName();
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }

    private ExecutableMapping parseExecutable(String name, Element executable, String defaultPackage, boolean beanAnnotationsIgnored) {
        boolean executableAnnotationsIgnored = booleanAttribute(executable, ATTRIBUTE_IGNORE_ANNOTATIONS, beanAnnotationsIgnored);
        List<ParameterMapping> parameters = new ArrayList<>();
        ElementMapping crossParameter = ElementMapping.empty(executableAnnotationsIgnored);
        ElementMapping returnValue = ElementMapping.empty(executableAnnotationsIgnored);
        NodeList children = executable.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            switch (localName(element)) {
                case "parameter" -> {
                    MutableAnnotationMetadata parameterMetadata = new MutableAnnotationMetadata();
                    parseElementMetadata(element, defaultPackage, parameterMetadata);
                    Class<?> parameterType = loadClass(resolveClassName(requireAttribute(element, "type"), defaultPackage));
                    parameters.add(new ParameterMapping(
                        parameterType,
                        parameterMetadata,
                        booleanAttribute(element, ATTRIBUTE_IGNORE_ANNOTATIONS, executableAnnotationsIgnored),
                        List.of()
                    ));
                }
                case "cross-parameter" -> {
                    MutableAnnotationMetadata crossParameterMetadata = new MutableAnnotationMetadata();
                    parseConstraints(element, defaultPackage, crossParameterMetadata);
                    crossParameter = new ElementMapping(
                        crossParameterMetadata,
                        booleanAttribute(element, ATTRIBUTE_IGNORE_ANNOTATIONS, executableAnnotationsIgnored),
                        List.of()
                    );
                }
                case "return-value" -> {
                    MutableAnnotationMetadata returnValueMetadata = new MutableAnnotationMetadata();
                    parseElementMetadata(element, defaultPackage, returnValueMetadata);
                    returnValue = new ElementMapping(
                        returnValueMetadata,
                        booleanAttribute(element, ATTRIBUTE_IGNORE_ANNOTATIONS, executableAnnotationsIgnored),
                        List.of()
                    );
                }
                default -> {
                    // Executable child names are validated before parsing.
                }
            }
        }
        return new ExecutableMapping(name, null, List.copyOf(parameters), crossParameter, returnValue);
    }

    private void parseElementMetadata(Element element,
                                      String defaultPackage,
                                      MutableAnnotationMetadata metadata) {
        parseConstraints(element, defaultPackage, metadata);
        if (child(element, "valid") != null) {
            metadata.addDeclaredAnnotation(Valid.class.getName(), Map.of());
            metadata.addDeclaredAnnotation(ValidatedElement.class.getName(), Map.of());
        }
        parseGroupConversions(element, defaultPackage, metadata);
    }

    private void parseGroupSequence(Element parent,
                                    String defaultPackage,
                                    MutableAnnotationMetadata metadata) {
        Element groupSequence = child(parent, "group-sequence");
        if (groupSequence != null) {
            metadata.addDeclaredAnnotation(
                GroupSequence.class.getName(),
                Map.of(AnnotationMetadata.VALUE_MEMBER, classValues(groupSequence, defaultPackage))
            );
        }
    }

    private void parseConstraints(Element parent,
                                  String defaultPackage,
                                  MutableAnnotationMetadata metadata) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element constraint) || !"constraint".equals(localName(constraint))) {
                continue;
            }
            String annotationName = resolveClassName(requireAttribute(constraint, ELEMENT_ANNOTATION), defaultPackage);
            Class<? extends Annotation> annotationType = (Class<? extends Annotation>) loadClass(annotationName);
            Map<CharSequence, Object> values = constraintValues(constraint, annotationType, defaultPackage);
            validateMandatoryAnnotationMembers(annotationType, values);
            values.put(ValidationAnnotationUtil.CONSTRAINT_TYPE, annotationType);
            metadata.addDefaultAnnotationValues(annotationName, ValidationMetadataSupport.standardDefaults(annotationType));
            metadata.addDeclaredAnnotation(annotationName, values);
            metadata.addDeclaredAnnotation(ValidatedElement.class.getName(), Map.of());
            metadata.addDeclaredStereotype(List.of(annotationName), Constraint.class.getName(), Map.of());
        }
    }

    private void parseGroupConversions(Element parent,
                                       String defaultPackage,
                                       MutableAnnotationMetadata metadata) {
        for (Element convertGroup : children(parent, "convert-group")) {
            Class<?> from = convertGroup.hasAttribute("from")
                ? loadClass(resolveClassName(convertGroup.getAttribute("from"), defaultPackage))
                : Default.class;
            Class<?> to = loadClass(resolveClassName(requireAttribute(convertGroup, "to"), defaultPackage));
            metadata.addDeclaredRepeatable(
                ConvertGroup.List.class.getName(),
                AnnotationValue.builder(ConvertGroup.class)
                    .member("from", from)
                    .member("to", to)
                    .build()
            );
        }
    }

    private List<ContainerElementMapping> parseContainerElements(Element parent,
                                                                String defaultPackage, Argument<?> containerType) {
        List<Element> containerElementTypes = children(parent, "container-element-type");
        if (containerElementTypes.isEmpty()) {
            return List.of();
        }
        if (containerType.getTypeParameters().length == 0) {
            throw new ValidationException(
                    "Cannot configure container element constraints on non-generic type: "
                            + containerType.toString());
        }
        Argument<?>[] typeArguments = containerType.getTypeParameters();
        Set<Integer> configuredIndexes = new LinkedHashSet<>();
        List<ContainerElementMapping> mappings = new ArrayList<>();
        for (Element containerElementType : containerElementTypes) {
            int typeArgumentIndex = typeArgumentIndex(containerElementType, typeArguments.length, containerType);
            if (typeArgumentIndex < 0 || typeArgumentIndex >= typeArguments.length) {
                throw new ValidationException("Invalid container element type argument index " + typeArgumentIndex + " for " + containerType.toString());
            }
            if (!configuredIndexes.add(typeArgumentIndex)) {
                throw new ValidationException("Container element type argument configured more than once: " + typeArgumentIndex + " for " + containerType.toString());
            }
            Argument<?> elementType = typeArguments[typeArgumentIndex];
            MutableAnnotationMetadata metadata = new MutableAnnotationMetadata();
            parseElementMetadata(containerElementType, defaultPackage, metadata);
            ContainerElementMapping mapping = new ContainerElementMapping(
                            containerType.getType(),
                            typeArgumentIndex,
                            elementType.getType(),
                metadata,
                parseContainerElements(containerElementType, defaultPackage, elementType)
            );
            if (mapping.isConstrained()) {
                mappings.add(mapping);
            }
        }
        return List.copyOf(mappings);
    }

    private static int typeArgumentIndex(Element containerElementType, int typeArgumentCount, Argument<?> containerType) {
        if (containerElementType.hasAttribute("type-argument-index")) {
            return Integer.parseInt(containerElementType.getAttribute("type-argument-index"));
        }
        if (typeArgumentCount == 1) {
            return 0;
        }
        throw new ValidationException(
                "Missing required validation XML attribute type-argument-index on"
                        + " container-element-type for "
                        + containerType.toString());
    }

    private void validateMandatoryAnnotationMembers(
            Class<? extends Annotation> type, Map<CharSequence, Object> values) {
        ValidationMetadataSupport.annotationMembers(type)
                .forEach(
                        (name, member) -> {
                            if (member.required()
                                    && !RESERVED_CONSTRAINT_ELEMENT_NAMES.contains(name)
                                    && !values.containsKey(name)) {
                                throw new ValidationException(
                                        "Missing mandatory annotation member in validation XML: "
                                                + type.getName()
                                                + "."
                                                + name);
                            }
                        });
    }

    private Map<CharSequence, Object> constraintValues(Element constraint,
                                                       Class<? extends Annotation> annotationType,
                                                       String defaultPackage) {
        Map<CharSequence, Object> values = new LinkedHashMap<>();
        NodeList children = constraint.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            switch (localName(element)) {
                case ATTRIBUTE_MESSAGE -> values.put(ATTRIBUTE_MESSAGE, text(element));
                case ATTRIBUTE_GROUPS -> values.put(ATTRIBUTE_GROUPS, classValues(element, defaultPackage));
                case ATTRIBUTE_PAYLOAD -> values.put(ATTRIBUTE_PAYLOAD, classValues(element, defaultPackage));
                case "element" -> {
                    String name = requireAttribute(element, "name");
                    if (RESERVED_CONSTRAINT_ELEMENT_NAMES.contains(name)) {
                        throw new ValidationException(
                                "Reserved annotation member cannot be configured as an XML element:"
                                        + " "
                                        + name);
                    }
                    values.put(name, annotationMemberValue(annotationType, name, element, defaultPackage));
                }
                default -> {
                }
            }
        }
        return values;
    }

    private Object annotationMemberValue(Class<? extends Annotation> annotationType,
                                         String name,
                                         Element element,
                                         String defaultPackage) {
        AnnotationMember member =
                ValidationMetadataSupport.annotationMembers(annotationType).get(name);
        if (member == null) {
            throw new ValidationException(
                    "Unknown annotation member " + annotationType.getName() + "." + name);
        }
        return convertValue(member.type(), element, defaultPackage);
    }

    private Object convertValue(Class<?> targetType, Element element, String defaultPackage) {
        try {
            if (targetType.isArray()) {
                return arrayValue(targetType.getComponentType(), element, defaultPackage);
            }
            String value = singleValue(element);
            Object scalarValue = scalarValue(targetType, value);
            if (scalarValue != null) {
                return scalarValue;
            }
            if (targetType == Class.class) {
                return loadClass(resolveClassName(value, defaultPackage));
            }
            if (targetType.isEnum()) {
                return ValidationMetadataSupport.enumConstants(targetType).stream()
                        .filter(constant -> constant.name().equals(value))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ValidationException(
                                                "Unknown enum constant "
                                                        + targetType.getName()
                                                        + "."
                                                        + value));
            }
            if (targetType.isAnnotation()) {
                Element annotationElement = child(element, ELEMENT_ANNOTATION);
                if (annotationElement == null) {
                    throw new ValidationException("Missing nested annotation value for " + targetType.getName());
                }
                return annotationValue((Class<? extends Annotation>) targetType, annotationElement, defaultPackage);
            }
            throw new ValidationException("Unsupported XML annotation member type: " + targetType.getName());
        } catch (RuntimeException e) {
            if (e instanceof ValidationException validationException) {
                throw validationException;
            }
            throw new ValidationException("Cannot convert XML annotation member value to " + targetType.getName(), e);
        }
    }

    @Nullable
    private static Object scalarValue(Class<?> targetType, String value) {
        if (targetType == String.class) {
            return value;
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return Byte.parseByte(value);
        }
        if (targetType == short.class || targetType == Short.class) {
            return Short.parseShort(value);
        }
        if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(value);
        }
        if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(value);
        }
        if (targetType == float.class || targetType == Float.class) {
            return Float.parseFloat(value);
        }
        if (targetType == double.class || targetType == Double.class) {
            return Double.parseDouble(value);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(value);
        }
        return characterValue(targetType, value);
    }

    @Nullable
    private static Character characterValue(Class<?> targetType, String value) {
        if (targetType != char.class && targetType != Character.class) {
            return null;
        }
        if (value.length() != 1) {
            throw new ValidationException("Value is not a single character: " + value);
        }
        return value.charAt(0);
    }

    private Object arrayValue(Class<?> componentType, Element element, String defaultPackage) {
        List<Element> valueElements = children(element, componentType.isAnnotation() ? ELEMENT_ANNOTATION : ELEMENT_VALUE);
        if (componentType.isAnnotation()) {
            AnnotationValue<?>[] values = valueElements.stream()
                .map(value -> annotationValue((Class<? extends Annotation>) componentType, value, defaultPackage))
                .toArray(AnnotationValue[]::new);
            return values;
        }
        int size = valueElements.size();
        Object array =
                componentType == byte.class
                        ? new byte[size]
                        : componentType == short.class
                                ? new short[size]
                                : componentType == int.class
                                        ? new int[size]
                                        : componentType == long.class
                                                ? new long[size]
                                                : componentType == float.class
                                                        ? new float[size]
                                                        : componentType == double.class
                                                                ? new double[size]
                                                                : componentType == boolean.class
                                                                        ? new boolean[size]
                                                                        : componentType
                                                                                        == char
                                                                                                .class
                                                                                ? new char[size]
                                                                                : componentType
                                                                                                == String
                                                                                                        .class
                                                                                        ? new String
                                                                                                [size]
                                                                                        : componentType
                                                                                                        == Class
                                                                                                                .class
                                                                                                ? new Class<
                                                                                                                ?>
                                                                                                        [size]
                                                                                                : ValidationMetadataSupport
                                                                                                        .typedArray(
                                                                                                                componentType,
                                                                                                                size);
        for (int i = 0; i < valueElements.size(); i++) {
            Object value = convertValue(componentType, valueElements.get(i), defaultPackage);
            switch (array) {
                case byte[] values -> values[i] = (Byte) value;
                case short[] values -> values[i] = (Short) value;
                case int[] values -> values[i] = (Integer) value;
                case long[] values -> values[i] = (Long) value;
                case float[] values -> values[i] = (Float) value;
                case double[] values -> values[i] = (Double) value;
                case boolean[] values -> values[i] = (Boolean) value;
                case char[] values -> values[i] = (Character) value;
                case Object[] values -> values[i] = value;
                default ->
                        throw new ValidationException(
                                "Unsupported array type " + componentType.getName());
            }
        }
        return array;
    }

    private AnnotationValue<?> annotationValue(Class<? extends Annotation> annotationType,
                                               Element annotation,
                                               String defaultPackage) {
        Map<CharSequence, Object> values = new LinkedHashMap<>();
        NodeList children = annotation.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && "element".equals(localName(element))) {
                String name = element.getAttribute("name");
                values.put(name, annotationMemberValue(annotationType, name, element, defaultPackage));
            }
        }
        return new AnnotationValue<>(annotationType.getName(), values);
    }

    private Class<?>[] classValues(Element parent, String defaultPackage) {
        List<Class<?>> values = new ArrayList<>();
        for (Element value : children(parent, ELEMENT_VALUE)) {
            values.add(loadClass(resolveClassName(text(value), defaultPackage)));
        }
        return values.toArray(Class<?>[]::new);
    }

    private String resolveClassName(String className, String defaultPackage) {
        if (Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double")
                .contains(className)) {
            return className;
        }
        if (className.startsWith("[L") && className.endsWith(";")) {
            String componentClassName = className.substring(2, className.length() - 1);
            return "[L" + resolveClassName(componentClassName, defaultPackage) + ";";
        }
        if (className.startsWith("[")) {
            return className;
        }
        if (className.indexOf('.') >= 0 || defaultPackage == null || defaultPackage.isEmpty()) {
            return className;
        }
        return defaultPackage + "." + className;
    }

    private Class<?> loadClass(String className) {
        return switch (className) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "char" -> char.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "java.lang.String" -> String.class;
            case "java.lang.Object" -> Object.class;
            default -> ValidationMetadataSupport.type(className, classLoader);
        };
    }

    private static String requireAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        if (value.isBlank()) {
            throw new ValidationException("Missing required validation XML attribute " + name + " on " + localName(element));
        }
        return value;
    }

    @Nullable
    private static Element child(Element parent, String name) {
        List<Element> children = children(parent, name);
        return children.isEmpty() ? null : children.get(0);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && name.equals(localName(element))) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static String textOfChild(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? "" : text(child);
    }

    private static String singleValue(Element element) {
        Element value = child(element, ELEMENT_VALUE);
        return value == null ? text(element) : text(value);
    }

    private static boolean booleanAttribute(Element element, String name, boolean defaultValue) {
        return element.hasAttribute(name) ? Boolean.parseBoolean(element.getAttribute(name)) : defaultValue;
    }

    /**
     * Validates the Jakarta Validation XML version attribute for either bootstrap configuration or
     * mapping XML.
     *
     * <p>This remains package-private so the bootstrap XML loader can reuse the same version checks
     * without introducing a public parser API.
     *
     * @param root The root XML element
     * @param supportedVersions The versions implemented by this module
     * @param resourceDescription Description used in validation errors
     */
    static void validateVersion(Element root, Set<String> supportedVersions, String resourceDescription) {
        String version = root.getAttribute("version");
        if (!version.isBlank() && !supportedVersions.contains(version)) {
            throw new ValidationException("Unsupported " + resourceDescription + " version: " + version);
        }
    }

    /**
     * Rejects root-level XML elements outside the subset this module intentionally implements.
     *
     * <p>Maintainers should update the allow-list and downstream parsing in the same change when
     * adding XML elements, so unsupported specification features fail deterministically instead of
     * being ignored.
     *
     * @param root The root XML element
     * @param allowedElementNames Local names accepted below the root
     * @param resourceDescription Description used in validation errors
     */
    static void validateRootElements(Element root, Set<String> allowedElementNames, String resourceDescription) {
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && !allowedElementNames.contains(localName(element))) {
                throw new ValidationException("Unsupported " + resourceDescription + " element: " + localName(element));
            }
        }
    }

    private static String localName(Element element) {
        String localName = element.getLocalName();
        return localName == null ? element.getTagName() : localName;
    }

    private static String text(Element element) {
        return element.getTextContent().trim();
    }

    private record BeanMapping(AnnotationMetadata classMetadata,
                               boolean beanAnnotationsIgnored,
                               boolean classAnnotationsIgnored,
                               Map<String, PropertyMapping> properties,
                               Map<ExecutableKey, ExecutableMapping> methods,
                               Map<ExecutableKey, ExecutableMapping> constructors) {
    }

    private record ConstraintDefinition(List<Class<?>> validatorClasses,
                                        boolean includeExistingValidators) {
    }

    private record PropertyMapping(AnnotationMetadata metadata,
                                   boolean annotationsIgnored,
            ValidationDeclaration source,
                                   Class<?> elementClass,
                                   List<ContainerElementMapping> containerElements) {
    }

    private record ExecutableKey(String name, List<Class<?>> parameterTypes) {
    }

    private record ExecutableMapping(String name,
                                     @Nullable ValidationDeclaration source,
            List<ParameterMapping> parameters,
            ElementMapping crossParameter,
            ElementMapping returnValue) {

        /**
         * The executable the mapping names, which the parsing of a mapping resolves against the
         * bean type before anything reads it.
         */
        ValidationDeclaration resolvedSource() {
            return Objects.requireNonNull(source, "The executable of a validation XML mapping is resolved as it is parsed");
        }

        List<Class<?>> parameterTypes() {
            return parameters.stream()
                .map(ParameterMapping::type)
                .toList();
        }

        ExecutableMapping withSource(ValidationDeclaration source) {
            return new ExecutableMapping(name, source, parameters, crossParameter, returnValue);
        }

        ExecutableMapping withContainerElements(Element executableElement,
                                                String defaultPackage,
                                                XmlValidationMetadataProvider provider) {
            List<Argument<?>> genericParameterTypes = resolvedSource().parameters();
            List<ParameterMapping> resolvedParameters = new ArrayList<>(parameters.size());
            int parameterIndex = 0;
            NodeList children = executableElement.getChildNodes();
            ElementMapping resolvedReturnValue = returnValue;
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (!(node instanceof Element element)) {
                    continue;
                }
                switch (localName(element)) {
                    case "parameter" -> {
                        ParameterMapping parameter = parameters.get(parameterIndex);
                        resolvedParameters.add(parameter.withContainerElements(
                            provider.parseContainerElements(element, defaultPackage, genericParameterTypes.get(parameterIndex))));
                        parameterIndex++;
                    }
                    case "return-value" -> {
                        Argument<?> returnType = resolvedSource().argument();
                        resolvedReturnValue = returnValue.withContainerElements(
                            provider.parseContainerElements(element, defaultPackage, returnType)
                        );
                    }
                    default -> {
                    }
                }
            }
            return new ExecutableMapping(name, source, List.copyOf(resolvedParameters), crossParameter, resolvedReturnValue);
        }
    }

    private record ParameterMapping(Class<?> type,
                                    AnnotationMetadata metadata,
                                    boolean annotationsIgnored,
                                    List<ContainerElementMapping> containerElements) {

        ParameterMapping withContainerElements(List<ContainerElementMapping> containerElements) {
            return new ParameterMapping(type, metadata, annotationsIgnored, containerElements);
        }
    }

    private record ElementMapping(AnnotationMetadata metadata,
                                  boolean annotationsIgnored,
                                  List<ContainerElementMapping> containerElements) {

        private static ElementMapping empty(boolean annotationsIgnored) {
            return new ElementMapping(AnnotationMetadata.EMPTY_METADATA, annotationsIgnored, List.of());
        }

        ElementMapping withContainerElements(List<ContainerElementMapping> containerElements) {
            return new ElementMapping(metadata, annotationsIgnored, containerElements);
        }
    }

    private record ContainerElementMapping(Class<?> containerClass,
                                           int typeArgumentIndex,
                                           Class<?> elementClass,
                                           AnnotationMetadata metadata,
                                           List<ContainerElementMapping> containerElements) {

        private boolean isConstrained() {
            return metadata.hasStereotype(Constraint.class)
                || metadata.hasAnnotation(Valid.class)
                || !metadata.getAnnotationValuesByType(ConvertGroup.class).isEmpty()
                || !containerElements.isEmpty();
        }
    }
}
