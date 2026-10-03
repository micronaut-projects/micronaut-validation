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
import io.micronaut.core.type.Argument;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.validation.annotation.ValidatedElement;
import io.micronaut.validation.validator.ReflectionSupport;
import io.micronaut.validation.validator.ValidationAnnotationUtil;
import io.micronaut.validation.validator.metadata.AnnotationMember;
import io.micronaut.validation.validator.metadata.ValidationDeclaration;
import io.micronaut.validation.validator.metadata.ValidationMetadataSupport;
import jakarta.validation.Constraint;
import jakarta.validation.GroupSequence;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import javax.xml.parsers.ParserConfigurationException;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.micronaut.validation.xml.XmlMapping.BeanMapping;
import static io.micronaut.validation.xml.XmlMapping.ConstraintDefinition;
import static io.micronaut.validation.xml.XmlMapping.ContainerElementMapping;
import static io.micronaut.validation.xml.XmlMapping.ElementMapping;
import static io.micronaut.validation.xml.XmlMapping.ExecutableKey;
import static io.micronaut.validation.xml.XmlMapping.ExecutableMapping;
import static io.micronaut.validation.xml.XmlMapping.ParameterMapping;
import static io.micronaut.validation.xml.XmlMapping.PropertyMapping;
import static io.micronaut.validation.xml.XmlMappingSupport.booleanAttribute;
import static io.micronaut.validation.xml.XmlMappingSupport.child;
import static io.micronaut.validation.xml.XmlMappingSupport.children;
import static io.micronaut.validation.xml.XmlMappingSupport.localName;
import static io.micronaut.validation.xml.XmlMappingSupport.requireAttribute;
import static io.micronaut.validation.xml.XmlMappingSupport.simpleName;
import static io.micronaut.validation.xml.XmlMappingSupport.singleValue;
import static io.micronaut.validation.xml.XmlMappingSupport.text;
import static io.micronaut.validation.xml.XmlMappingSupport.textOfChild;
import static io.micronaut.validation.xml.XmlMappingSupport.validateMappingStructure;
import static io.micronaut.validation.xml.XmlMappingSupport.validateRootElements;
import static io.micronaut.validation.xml.XmlMappingSupport.validateVersion;

/** Parses and validates constraint mapping documents before metadata is exposed. */
final class XmlMappingParser {
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

    final Map<Class<?>, BeanMapping> beanMappings = new LinkedHashMap<>();
    final Map<String, ConstraintDefinition> constraintDefinitions = new LinkedHashMap<>();
    private final ClassLoader classLoader;
    private final ReflectionSupport reflectionSupport;

    XmlMappingParser(ReflectionSupport reflectionSupport, Set<InputStream> mappingStreams) {
        this.classLoader = reflectionSupport.classLoader();
        this.reflectionSupport = reflectionSupport;
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

    private void parse(InputStream inputStream) {
        try {
            Document document = SecureXmlDocumentBuilder.parse(inputStream);
            Element root = document.getDocumentElement();
            validateVersion(root, SUPPORTED_MAPPING_VERSIONS, "constraint mapping XML");
            validateRootElements(root, ROOT_ELEMENT_NAMES, "constraint mapping XML");
            validateMappingStructure(root);
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
            throw new ValidationException("Unknown " + elementName + " in validation XML: " + beanType.getName() + "." + propertyName
                + ". A type declaring no constraint is described for an XML mapping when compiled with the annotation"
                + " processor option micronaut.validation.describeAllIntrospections=true");
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

    private void validatePropertyConfiguredOnce(Class<?> beanType,
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

    private void validateGetterConfiguredOnce(Class<?> beanType,
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
    private ValidationDeclaration findPropertySource(
            Class<?> beanType, String elementName, String propertyName) {
        return reflectionSupport.declaration(beanType, elementName, propertyName, List.of());
    }

    private Set<String> getterMethodNames(Class<?> beanType, String propertyName) {
        ValidationDeclaration getter = findPropertySource(beanType, ELEMENT_GETTER, propertyName);
        return getter == null ? Set.of() : Set.of(getter.name());
    }

    @Nullable
    private ValidationDeclaration findConstructor(
            Class<?> beanType, List<Class<?>> parameters) {
        return reflectionSupport.declaration(beanType, "constructor", simpleName(beanType), parameters);
    }

    @Nullable
    private ValidationDeclaration findMethod(
            Class<?> beanType, String name, List<Class<?>> parameters) {
        return reflectionSupport.declaration(beanType, "method", name, parameters);
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

    List<ContainerElementMapping> parseContainerElements(Element parent,
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
        ValidationMetadataSupport.annotationMembers(reflectionSupport, type)
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
                ValidationMetadataSupport.annotationMembers(reflectionSupport, annotationType).get(name);
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
                return ValidationMetadataSupport.enumConstants(reflectionSupport, targetType).stream()
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
    private Object scalarValue(Class<?> targetType, String value) {
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
        Object array = newArray(componentType, size);
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

    private Object newArray(Class<?> componentType, int size) {
        if (componentType == byte.class) {
            return new byte[size];
        }
        if (componentType == short.class) {
            return new short[size];
        }
        if (componentType == int.class) {
            return new int[size];
        }
        if (componentType == long.class) {
            return new long[size];
        }
        if (componentType == float.class) {
            return new float[size];
        }
        if (componentType == double.class) {
            return new double[size];
        }
        if (componentType == boolean.class) {
            return new boolean[size];
        }
        if (componentType == char.class) {
            return new char[size];
        }
        if (componentType == String.class) {
            return new String[size];
        }
        if (componentType == Class.class) {
            return new Class<?>[size];
        }
        return ValidationMetadataSupport.typedArray(reflectionSupport, componentType, size);
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
            default -> ValidationMetadataSupport.type(reflectionSupport, className, classLoader);
        };
    }

}
