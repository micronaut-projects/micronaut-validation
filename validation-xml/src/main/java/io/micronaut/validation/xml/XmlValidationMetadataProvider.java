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
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.GroupSequence;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.BeanDescriptor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads Jakarta Validation constraint mapping XML as validation metadata.
 *
 * @since 5.1
 */
@Internal
public final class XmlValidationMetadataProvider implements ValidationMetadataProvider {

    private static final Set<String> RESERVED_CONSTRAINT_ELEMENT_NAMES = Set.of("message", "groups", "payload");

    private final Map<Class<?>, BeanMapping> beanMappings = new LinkedHashMap<>();
    private final Map<String, ConstraintDefinition> constraintDefinitions = new LinkedHashMap<>();
    private final ClassLoader classLoader;

    /**
     * @param classLoader The class loader
     * @param mappingStreams The mapping streams
     */
    public XmlValidationMetadataProvider(ClassLoader classLoader, Set<InputStream> mappingStreams) {
        this.classLoader = classLoader;
        for (InputStream mappingStream : mappingStreams) {
            parse(mappingStream);
        }
    }

    @Override
    public Optional<BeanDescriptor> getConstraintsForClass(Class<?> beanType) {
        return Optional.empty();
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
                Constraint constraint = constraintType.getAnnotation(Constraint.class);
                if (constraint != null) {
                    validatorClasses.addAll((List) List.of(constraint.validatedBy()));
                }
            }
        }
        validatorClasses.addAll((List) constraintDefinition.validatorClasses());
        return Optional.of(List.copyOf(validatorClasses));
    }

    private void parse(InputStream inputStream) {
        try (inputStream) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document document = factory.newDocumentBuilder().parse(inputStream);
            Element root = document.getDocumentElement();
            String defaultPackage = textOfChild(root, "default-package");
            Map<String, ConstraintDefinition> mappingConstraintDefinitions = constraintDefinitions(root, defaultPackage);
            constraintDefinitions.putAll(mappingConstraintDefinitions);
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
            String annotationName = resolveClassName(requireAttribute(definition, "annotation"), defaultPackage);
            Element validatedBy = child(definition, "validated-by");
            if (validatedBy == null) {
                continue;
            }
            boolean includeExistingValidators = booleanAttribute(validatedBy, "include-existing-validators", true);
            List<Class<?>> validators = new ArrayList<>();
            NodeList validatorNodes = validatedBy.getChildNodes();
            for (int j = 0; j < validatorNodes.getLength(); j++) {
                Node validatorNode = validatorNodes.item(j);
                if (validatorNode instanceof Element value && "value".equals(localName(value))) {
                    validators.add(loadClass(resolveClassName(text(value), defaultPackage)));
                }
            }
            definitions.put(annotationName, new ConstraintDefinition(List.copyOf(validators), includeExistingValidators));
        }
        return definitions;
    }

    private void parseBean(Element bean, String defaultPackage) {
        Class<?> beanType = loadClass(resolveClassName(requireAttribute(bean, "class"), defaultPackage));
        boolean beanAnnotationsIgnored = booleanAttribute(bean, "ignore-annotations", true);
        MutableAnnotationMetadata classMetadata = new MutableAnnotationMetadata();
        boolean classAnnotationsIgnored = beanAnnotationsIgnored;
        Map<String, PropertyMapping> properties = new LinkedHashMap<>();
        NodeList children = bean.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            String elementName = localName(element);
            switch (elementName) {
                case "class" -> {
                    classAnnotationsIgnored = booleanAttribute(element, "ignore-annotations", beanAnnotationsIgnored);
                    parseGroupSequence(element, defaultPackage, classMetadata);
                    parseConstraints(element, defaultPackage, classMetadata);
                }
                case "field", "getter" -> {
                    String propertyName = requireAttribute(element, "name");
                    validatePropertyExists(beanType, elementName, propertyName);
                    MutableAnnotationMetadata propertyMetadata = new MutableAnnotationMetadata();
                    parseConstraints(element, defaultPackage, propertyMetadata);
                    if (child(element, "valid") != null) {
                        propertyMetadata.addDeclaredAnnotation(Valid.class.getName(), Map.of());
                    }
                    parseGroupConversions(element, defaultPackage, propertyMetadata);
                    boolean propertyAnnotationsIgnored = booleanAttribute(element, "ignore-annotations", beanAnnotationsIgnored);
                    properties.put(propertyName, new PropertyMapping(propertyMetadata, propertyAnnotationsIgnored));
                }
                default -> {
                }
            }
        }
        beanMappings.put(beanType, new BeanMapping(classMetadata, beanAnnotationsIgnored, classAnnotationsIgnored, properties));
    }

    private static void validatePropertyExists(Class<?> beanType, String elementName, String propertyName) {
        boolean found = switch (elementName) {
            case "field" -> hasField(beanType, propertyName);
            case "getter" -> hasGetter(beanType, propertyName);
            default -> true;
        };
        if (!found) {
            throw new ValidationException("Unknown " + elementName + " in validation XML: " + beanType.getName() + "." + propertyName);
        }
    }

    private static boolean hasField(Class<?> beanType, String fieldName) {
        Class<?> currentType = beanType;
        while (currentType != null && currentType != Object.class) {
            try {
                currentType.getDeclaredField(fieldName);
                return true;
            } catch (NoSuchFieldException e) {
                currentType = currentType.getSuperclass();
            }
        }
        return false;
    }

    private static boolean hasGetter(Class<?> beanType, String propertyName) {
        String suffix = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        return hasGetterMethod(beanType, "get" + suffix, false) || hasGetterMethod(beanType, "is" + suffix, true);
    }

    private static boolean hasGetterMethod(Class<?> beanType, String methodName, boolean booleanGetter) {
        Class<?> currentType = beanType;
        while (currentType != null && currentType != Object.class) {
            for (Method method : currentType.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && method.getName().equals(methodName)) {
                    Class<?> returnType = method.getReturnType();
                    return returnType != void.class && (!booleanGetter || returnType == boolean.class || returnType == Boolean.class);
                }
            }
            currentType = currentType.getSuperclass();
        }
        return false;
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
            String annotationName = resolveClassName(requireAttribute(constraint, "annotation"), defaultPackage);
            Class<? extends Annotation> annotationType = (Class<? extends Annotation>) loadClass(annotationName);
            Map<CharSequence, Object> values = constraintValues(constraint, annotationType, defaultPackage);
            validateMandatoryAnnotationMembers(annotationType, values);
            metadata.addDeclaredAnnotation(annotationName, values);
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

    private void validateMandatoryAnnotationMembers(Class<? extends Annotation> annotationType, Map<CharSequence, Object> values) {
        for (Method method : annotationType.getDeclaredMethods()) {
            if (method.getDefaultValue() == null
                && !RESERVED_CONSTRAINT_ELEMENT_NAMES.contains(method.getName())
                && !values.containsKey(method.getName())) {
                throw new ValidationException("Missing mandatory annotation member in validation XML: " + annotationType.getName() + "." + method.getName());
            }
        }
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
                case "message" -> values.put("message", text(element));
                case "groups" -> values.put("groups", classValues(element, defaultPackage));
                case "payload" -> values.put("payload", classValues(element, defaultPackage));
                case "element" -> {
                    String name = requireAttribute(element, "name");
                    if (RESERVED_CONSTRAINT_ELEMENT_NAMES.contains(name)) {
                        throw new ValidationException("Reserved annotation member cannot be configured as an XML element: " + name);
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
        try {
            Method method = annotationType.getDeclaredMethod(name);
            return convertValue(method.getReturnType(), element, defaultPackage);
        } catch (NoSuchMethodException e) {
            throw new ValidationException("Unknown annotation member " + annotationType.getName() + "." + name, e);
        }
    }

    private Object convertValue(Class<?> targetType, Element element, String defaultPackage) {
        try {
            if (targetType.isArray()) {
                return arrayValue(targetType.getComponentType(), element, defaultPackage);
            }
            String value = singleValue(element);
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
            if (targetType == char.class || targetType == Character.class) {
                if (value.length() != 1) {
                    throw new ValidationException("Value is not a single character: " + value);
                }
                return value.charAt(0);
            }
            if (targetType == Class.class) {
                return loadClass(resolveClassName(value, defaultPackage));
            }
            if (targetType.isEnum()) {
                return Enum.valueOf((Class<? extends Enum>) targetType, value);
            }
            if (targetType.isAnnotation()) {
                Element annotationElement = child(element, "annotation");
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

    private Object arrayValue(Class<?> componentType, Element element, String defaultPackage) {
        List<Element> valueElements = children(element, componentType.isAnnotation() ? "annotation" : "value");
        if (componentType.isAnnotation()) {
            AnnotationValue<?>[] values = valueElements.stream()
                .map(value -> annotationValue((Class<? extends Annotation>) componentType, value, defaultPackage))
                .toArray(AnnotationValue[]::new);
            return values;
        }
        Object array = Array.newInstance(componentType, valueElements.size());
        for (int i = 0; i < valueElements.size(); i++) {
            Array.set(array, i, convertValue(componentType, valueElements.get(i), defaultPackage));
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
        for (Element value : children(parent, "value")) {
            values.add(loadClass(resolveClassName(text(value), defaultPackage)));
        }
        return values.toArray(Class<?>[]::new);
    }

    private String resolveClassName(String className, String defaultPackage) {
        if (className.indexOf('.') >= 0 || defaultPackage == null || defaultPackage.isEmpty()) {
            return className;
        }
        return defaultPackage + "." + className;
    }

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new ValidationException("Cannot load class from validation XML: " + className, e);
        }
    }

    private static String requireAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        if (value.isBlank()) {
            throw new ValidationException("Missing required validation XML attribute " + name + " on " + localName(element));
        }
        return value;
    }

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
        Element value = child(element, "value");
        return value == null ? text(element) : text(value);
    }

    private static boolean booleanAttribute(Element element, String name, boolean defaultValue) {
        return element.hasAttribute(name) ? Boolean.parseBoolean(element.getAttribute(name)) : defaultValue;
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
                               Map<String, PropertyMapping> properties) {
    }

    private record ConstraintDefinition(List<Class<?>> validatorClasses,
                                        boolean includeExistingValidators) {
    }

    private record PropertyMapping(AnnotationMetadata metadata,
                                   boolean annotationsIgnored) {
    }
}
