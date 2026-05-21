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
import io.micronaut.inject.annotation.AnnotationMetadataSupport;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.GroupSequence;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ConstructorDescriptor;
import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.CrossParameterDescriptor;
import jakarta.validation.metadata.ElementDescriptor;
import jakarta.validation.metadata.GroupConversionDescriptor;
import jakarta.validation.metadata.MethodDescriptor;
import jakarta.validation.metadata.MethodType;
import jakarta.validation.metadata.ParameterDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;
import jakarta.validation.metadata.ReturnValueDescriptor;
import jakarta.validation.metadata.Scope;
import jakarta.validation.metadata.ValidateUnwrappedValue;
import jakarta.validation.valueextraction.Unwrapping;
import org.jspecify.annotations.Nullable;
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
import java.lang.annotation.ElementType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads Jakarta Validation constraint mapping XML as validation metadata.
 *
 * @since 5.1
 */
@Internal
public final class XmlValidationMetadataProvider implements ValidationMetadataProvider {

    private static final Set<String> RESERVED_CONSTRAINT_ELEMENT_NAMES = Set.of("message", "groups", "payload");
    private static final Set<String> SUPPORTED_MAPPING_VERSIONS = Set.of("1.0", "1.1", "2.0", "3.0", "3.1");
    private static final Set<String> ROOT_ELEMENT_NAMES = Set.of("default-package", "bean", "constraint-definition");

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
        BeanMapping mapping = beanMappings.get(beanType);
        return mapping == null ? Optional.empty() : Optional.of(new XmlBeanDescriptor(beanType, mapping));
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
        return method != null
            && parameterIndex < method.parameters.size()
            && method.parameters.get(parameterIndex).annotationsIgnored();
    }

    @Override
    public boolean isMethodReturnValueAnnotationMetadataIgnored(Class<?> beanType,
                                                               String methodName,
                                                               Class<?>[] parameterTypes) {
        ExecutableMapping method = methodMapping(beanType, methodName, parameterTypes);
        return method != null && method.returnValue.annotationsIgnored();
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
            validateVersion(root, SUPPORTED_MAPPING_VERSIONS, "constraint mapping XML");
            validateRootElements(root, ROOT_ELEMENT_NAMES, "constraint mapping XML");
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
                case "class" -> {
                    classAnnotationsIgnored = booleanAttribute(element, "ignore-annotations", beanAnnotationsIgnored);
                    parseGroupSequence(element, defaultPackage, classMetadata);
                    parseConstraints(element, defaultPackage, classMetadata);
                }
                case "field", "getter" -> {
                    String propertyName = requireAttribute(element, "name");
                    java.lang.reflect.AnnotatedElement source = findPropertySource(beanType, elementName, propertyName);
                    if (source == null) {
                        throw new ValidationException("Unknown " + elementName + " in validation XML: " + beanType.getName() + "." + propertyName);
                    }
                    if ("field".equals(elementName) && !configuredFields.add(propertyName)) {
                        throw new ValidationException("Field configured more than once in validation XML: " + beanType.getName() + "." + propertyName);
                    }
                    if ("getter".equals(elementName)) {
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
                    MutableAnnotationMetadata propertyMetadata = new MutableAnnotationMetadata();
                    parseConstraints(element, defaultPackage, propertyMetadata);
                    if (child(element, "valid") != null) {
                        propertyMetadata.addDeclaredAnnotation(Valid.class.getName(), Map.of());
                    }
                    parseGroupConversions(element, defaultPackage, propertyMetadata);
                    boolean propertyAnnotationsIgnored = booleanAttribute(element, "ignore-annotations", beanAnnotationsIgnored);
                    Type propertyType = propertyGenericType(source);
                    properties.put(propertyName, new PropertyMapping(
                        propertyMetadata,
                        propertyAnnotationsIgnored,
                        source,
                        propertyElementClass(source),
                        parseContainerElements(element, defaultPackage, propertyType)
                    ));
                }
                case "constructor" -> {
                    ExecutableMapping constructor = parseExecutable(beanType.getSimpleName(), element, defaultPackage, beanAnnotationsIgnored);
                    Constructor<?> source = findConstructor(beanType, constructor.parameterTypes());
                    if (source == null) {
                        throw new ValidationException("Unknown constructor in validation XML: " + beanType.getName() + constructor.parameterTypes());
                    }
                    constructor = constructor.withSource(source)
                        .withContainerElements(element, defaultPackage, this);
                    ExecutableKey key = new ExecutableKey(beanType.getSimpleName(), constructor.parameterTypes());
                    if (constructors.putIfAbsent(key, constructor) != null) {
                        throw new ValidationException("Constructor configured more than once in validation XML: " + beanType.getName() + constructor.parameterTypes());
                    }
                }
                case "method" -> {
                    String methodName = requireAttribute(element, "name");
                    ExecutableMapping method = parseExecutable(methodName, element, defaultPackage, beanAnnotationsIgnored);
                    Method source = findMethod(beanType, methodName, method.parameterTypes());
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
                default -> {
                }
            }
        }
        if (beanMappings.putIfAbsent(beanType, new BeanMapping(classMetadata, beanAnnotationsIgnored, classAnnotationsIgnored, properties, methods, constructors)) != null) {
            throw new ValidationException("Bean configured more than once in validation XML: " + beanType.getName());
        }
    }

    private static java.lang.reflect.AnnotatedElement findPropertySource(Class<?> beanType, String elementName, String propertyName) {
        return switch (elementName) {
            case "field" -> findField(beanType, propertyName);
            case "getter" -> findGetter(beanType, propertyName);
            default -> null;
        };
    }

    private static Field findField(Class<?> beanType, String fieldName) {
        Class<?> currentType = beanType;
        while (currentType != null && currentType != Object.class) {
            try {
                return currentType.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                currentType = currentType.getSuperclass();
            }
        }
        return null;
    }

    private static Method findGetter(Class<?> beanType, String propertyName) {
        String suffix = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        Method getter = findGetterMethod(beanType, "get" + suffix, false);
        return getter == null ? findGetterMethod(beanType, "is" + suffix, true) : getter;
    }

    private static Set<String> getterMethodNames(Class<?> beanType, String propertyName) {
        String suffix = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        Set<String> methodNames = new LinkedHashSet<>();
        if (findGetterMethod(beanType, "get" + suffix, false) != null) {
            methodNames.add("get" + suffix);
        }
        if (findGetterMethod(beanType, "is" + suffix, true) != null) {
            methodNames.add("is" + suffix);
        }
        return methodNames;
    }

    private static Method findGetterMethod(Class<?> beanType, String methodName, boolean booleanGetter) {
        Class<?> currentType = beanType;
        while (currentType != null && currentType != Object.class) {
            for (Method method : currentType.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && method.getName().equals(methodName)) {
                    Class<?> returnType = method.getReturnType();
                    if (returnType != void.class && (!booleanGetter || returnType == boolean.class || returnType == Boolean.class)) {
                        return method;
                    }
                }
            }
            currentType = currentType.getSuperclass();
        }
        return null;
    }

    private static Class<?> propertyElementClass(java.lang.reflect.AnnotatedElement source) {
        if (source instanceof Field field) {
            return field.getType();
        }
        if (source instanceof Method method) {
            return method.getReturnType();
        }
        return Object.class;
    }

    private static Type propertyGenericType(java.lang.reflect.AnnotatedElement source) {
        if (source instanceof Field field) {
            return field.getGenericType();
        }
        if (source instanceof Method method) {
            return method.getGenericReturnType();
        }
        return Object.class;
    }

    private static Constructor<?> findConstructor(Class<?> beanType, List<Class<?>> parameterTypes) {
        try {
            return beanType.getDeclaredConstructor(parameterTypes.toArray(Class<?>[]::new));
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Method findMethod(Class<?> beanType, String methodName, List<Class<?>> parameterTypes) {
        Class<?> currentType = beanType;
        while (currentType != null && currentType != Object.class) {
            for (Method method : currentType.getDeclaredMethods()) {
                if (method.getName().equals(methodName)
                    && Arrays.equals(method.getParameterTypes(), parameterTypes.toArray(Class<?>[]::new))) {
                    return method;
                }
            }
            currentType = currentType.getSuperclass();
        }
        return null;
    }

    private ExecutableMapping parseExecutable(String name, Element executable, String defaultPackage, boolean beanAnnotationsIgnored) {
        boolean executableAnnotationsIgnored = booleanAttribute(executable, "ignore-annotations", beanAnnotationsIgnored);
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
                        booleanAttribute(element, "ignore-annotations", executableAnnotationsIgnored),
                        List.of()
                    ));
                }
                case "cross-parameter" -> {
                    MutableAnnotationMetadata crossParameterMetadata = new MutableAnnotationMetadata();
                    parseConstraints(element, defaultPackage, crossParameterMetadata);
                    crossParameter = new ElementMapping(
                        crossParameterMetadata,
                        booleanAttribute(element, "ignore-annotations", executableAnnotationsIgnored),
                        List.of()
                    );
                }
                case "return-value" -> {
                    MutableAnnotationMetadata returnValueMetadata = new MutableAnnotationMetadata();
                    parseElementMetadata(element, defaultPackage, returnValueMetadata);
                    returnValue = new ElementMapping(
                        returnValueMetadata,
                        booleanAttribute(element, "ignore-annotations", executableAnnotationsIgnored),
                        List.of()
                    );
                }
                default -> {
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

    private List<ContainerElementMapping> parseContainerElements(Element parent,
                                                                String defaultPackage,
                                                                Type containerType) {
        List<Element> containerElementTypes = children(parent, "container-element-type");
        if (containerElementTypes.isEmpty()) {
            return List.of();
        }
        if (!(containerType instanceof ParameterizedType parameterizedType)) {
            throw new ValidationException("Cannot configure container element constraints on non-generic type: " + containerType.getTypeName());
        }
        Type[] typeArguments = parameterizedType.getActualTypeArguments();
        Set<Integer> configuredIndexes = new LinkedHashSet<>();
        List<ContainerElementMapping> mappings = new ArrayList<>();
        for (Element containerElementType : containerElementTypes) {
            int typeArgumentIndex = typeArgumentIndex(containerElementType, typeArguments.length, containerType);
            if (typeArgumentIndex < 0 || typeArgumentIndex >= typeArguments.length) {
                throw new ValidationException("Invalid container element type argument index " + typeArgumentIndex + " for " + containerType.getTypeName());
            }
            if (!configuredIndexes.add(typeArgumentIndex)) {
                throw new ValidationException("Container element type argument configured more than once: " + typeArgumentIndex + " for " + containerType.getTypeName());
            }
            Type elementType = typeArguments[typeArgumentIndex];
            MutableAnnotationMetadata metadata = new MutableAnnotationMetadata();
            parseElementMetadata(containerElementType, defaultPackage, metadata);
            ContainerElementMapping mapping = new ContainerElementMapping(
                classFromType(parameterizedType.getRawType()),
                typeArgumentIndex,
                classFromType(elementType),
                metadata,
                parseContainerElements(containerElementType, defaultPackage, elementType)
            );
            if (mapping.isConstrained()) {
                mappings.add(mapping);
            }
        }
        return List.copyOf(mappings);
    }

    private static int typeArgumentIndex(Element containerElementType, int typeArgumentCount, Type containerType) {
        if (containerElementType.hasAttribute("type-argument-index")) {
            return Integer.parseInt(containerElementType.getAttribute("type-argument-index"));
        }
        if (typeArgumentCount == 1) {
            return 0;
        }
        throw new ValidationException("Missing required validation XML attribute type-argument-index on container-element-type for " + containerType.getTypeName());
    }

    private static Class<?> classFromType(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return classFromType(parameterizedType.getRawType());
        }
        return Object.class;
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

    static void validateVersion(Element root, Set<String> supportedVersions, String resourceDescription) {
        String version = root.getAttribute("version");
        if (!version.isBlank() && !supportedVersions.contains(version)) {
            throw new ValidationException("Unsupported " + resourceDescription + " version: " + version);
        }
    }

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

    private static Set<GroupConversionDescriptor> groupConversions(AnnotationMetadata annotationMetadata) {
        List<AnnotationValue<ConvertGroup>> conversions = annotationMetadata.getAnnotationValuesByType(ConvertGroup.class);
        if (conversions.isEmpty()) {
            return Collections.emptySet();
        }
        Set<GroupConversionDescriptor> descriptors = new LinkedHashSet<>();
        for (AnnotationValue<ConvertGroup> conversion : conversions) {
            Class<?> from = conversion.classValue("from").orElse(Default.class);
            Class<?> to = conversion.classValue("to").orElseThrow();
            descriptors.add(new XmlGroupConversionDescriptor(from, to));
        }
        return descriptors;
    }

    private static Set<GroupConversionDescriptor> groupConversions(AnnotationMetadata annotationMetadata,
                                                                   java.lang.reflect.AnnotatedElement annotatedElement,
                                                                   boolean annotationsIgnored) {
        Set<GroupConversionDescriptor> descriptors = new LinkedHashSet<>(groupConversions(annotationMetadata));
        if (!annotationsIgnored) {
            ConvertGroup convertGroup = annotatedElement.getAnnotation(ConvertGroup.class);
            if (convertGroup != null) {
                descriptors.add(new XmlGroupConversionDescriptor(convertGroup.from(), convertGroup.to()));
            }
            ConvertGroup.List convertGroups = annotatedElement.getAnnotation(ConvertGroup.List.class);
            if (convertGroups != null) {
                for (ConvertGroup listedConvertGroup : convertGroups.value()) {
                    descriptors.add(new XmlGroupConversionDescriptor(listedConvertGroup.from(), listedConvertGroup.to()));
                }
            }
        }
        return Set.copyOf(descriptors);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Set<ConstraintDescriptor<?>> constraintDescriptors(AnnotationMetadata annotationMetadata) {
        if (!annotationMetadata.hasStereotype(Constraint.class)) {
            return Collections.emptySet();
        }
        Set<ConstraintDescriptor<?>> descriptors = new LinkedHashSet<>();
        List<Class<? extends Annotation>> constraintTypes = annotationMetadata.getAnnotationTypesByStereotype(Constraint.class, currentClassLoader());
        for (Class<? extends Annotation> type : constraintTypes) {
            for (AnnotationValue<? extends Annotation> annotationValue : annotationMetadata.getAnnotationValuesByType(type)) {
                descriptors.add(new XmlConstraintDescriptor(type, annotationValue));
            }
        }
        return Set.copyOf(descriptors);
    }

    private static Set<ConstraintDescriptor<?>> constraintDescriptors(AnnotationMetadata annotationMetadata,
                                                                     java.lang.reflect.AnnotatedElement annotatedElement,
                                                                     boolean annotationsIgnored,
                                                                     ConstraintTarget target) {
        Set<ConstraintDescriptor<?>> descriptors = new LinkedHashSet<>(constraintDescriptors(annotationMetadata));
        if (!annotationsIgnored) {
            for (Annotation annotation : annotatedElement.getAnnotations()) {
                if (annotation.annotationType().isAnnotationPresent(Constraint.class) && appliesTo(annotation, target)) {
                    descriptors.add(new AnnotationConstraintDescriptor<>(annotation));
                }
            }
        }
        return Set.copyOf(descriptors);
    }

    private static boolean appliesTo(Annotation annotation, ConstraintTarget target) {
        Constraint constraint = annotation.annotationType().getAnnotation(Constraint.class);
        if (constraint == null) {
            return false;
        }
        Set<ValidationTarget> supportedTargets = new LinkedHashSet<>();
        for (Class<? extends ConstraintValidator<?, ?>> validatorClass : constraint.validatedBy()) {
            SupportedValidationTarget supportedValidationTarget = validatorClass.getAnnotation(SupportedValidationTarget.class);
            if (supportedValidationTarget == null) {
                supportedTargets.add(ValidationTarget.ANNOTATED_ELEMENT);
            } else {
                supportedTargets.addAll(Arrays.asList(supportedValidationTarget.value()));
            }
        }
        if (supportedTargets.isEmpty()) {
            supportedTargets.add(ValidationTarget.ANNOTATED_ELEMENT);
        }
        return target == ConstraintTarget.PARAMETERS
            ? supportedTargets.contains(ValidationTarget.PARAMETERS)
            : supportedTargets.contains(ValidationTarget.ANNOTATED_ELEMENT);
    }

    private static ClassLoader currentClassLoader() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        return classLoader == null ? XmlValidationMetadataProvider.class.getClassLoader() : classLoader;
    }

    private final class XmlBeanDescriptor implements BeanDescriptor, ElementDescriptor.ConstraintFinder {

        private final Class<?> beanType;
        private final BeanMapping mapping;

        private XmlBeanDescriptor(Class<?> beanType, BeanMapping mapping) {
            this.beanType = beanType;
            this.mapping = mapping;
        }

        @Override
        public boolean isBeanConstrained() {
            return !mapping.classMetadata().isEmpty() || !mapping.properties().isEmpty() || !mapping.methods().isEmpty() || !mapping.constructors().isEmpty();
        }

        @Override
        public PropertyDescriptor getConstraintsForProperty(String propertyName) {
            if (propertyName == null) {
                throw new IllegalArgumentException("Property name cannot be null");
            }
            return Optional.ofNullable(mapping.properties().get(propertyName))
                .map(mapping -> new XmlPropertyDescriptor(propertyName, mapping))
                .map(PropertyDescriptor.class::cast)
                .orElse(null);
        }

        @Override
        public Set<PropertyDescriptor> getConstrainedProperties() {
            return mapping.properties().entrySet()
                .stream()
                .map(entry -> new XmlPropertyDescriptor(entry.getKey(), entry.getValue()))
                .filter(XmlPropertyDescriptor::isConstrained)
                .collect(Collectors.toSet());
        }

        @Override
        public MethodDescriptor getConstraintsForMethod(String methodName, Class<?>... parameterTypes) {
            return Optional.ofNullable(mapping.methods().get(new ExecutableKey(methodName, Arrays.asList(parameterTypes))))
                .map(XmlMethodDescriptor::new)
                .orElse(null);
        }

        @Override
        public Set<MethodDescriptor> getConstrainedMethods(MethodType methodType, MethodType... methodTypes) {
            return mapping.methods().values()
                .stream()
                .map(XmlMethodDescriptor::new)
                .collect(Collectors.toSet());
        }

        @Override
        public ConstructorDescriptor getConstraintsForConstructor(Class<?>... parameterTypes) {
            return Optional.ofNullable(mapping.constructors().get(new ExecutableKey(beanType.getSimpleName(), Arrays.asList(parameterTypes))))
                .map(XmlConstructorDescriptor::new)
                .orElse(null);
        }

        @Override
        public Set<ConstructorDescriptor> getConstrainedConstructors() {
            return mapping.constructors().values()
                .stream()
                .map(XmlConstructorDescriptor::new)
                .collect(Collectors.toSet());
        }

        @Override
        public boolean hasConstraints() {
            return mapping.classMetadata().hasStereotype(Constraint.class);
        }

        @Override
        public Class<?> getElementClass() {
            return beanType;
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return constraintDescriptors(mapping.classMetadata());
        }

        @Override
        public ElementDescriptor.ConstraintFinder findConstraints() {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }
    }

    private record XmlPropertyDescriptor(String propertyName, PropertyMapping property)
        implements PropertyDescriptor, ElementDescriptor.ConstraintFinder {

        @Override
        public String getPropertyName() {
            return propertyName;
        }

        @Override
        public boolean isCascaded() {
            return property.metadata().hasAnnotation(Valid.class)
                || !property.annotationsIgnored() && property.source().isAnnotationPresent(Valid.class);
        }

        @Override
        public Set<GroupConversionDescriptor> getGroupConversions() {
            return groupConversions(property.metadata(), property.source(), property.annotationsIgnored());
        }

        @Override
        public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
            return containerElementDescriptors(property.containerElements());
        }

        @Override
        public boolean hasConstraints() {
            return !getConstraintDescriptors().isEmpty();
        }

        @Override
        public Class<?> getElementClass() {
            return property.elementClass();
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return constraintDescriptors(property.metadata(), property.source(), property.annotationsIgnored(), ConstraintTarget.IMPLICIT);
        }

        @Override
        public ElementDescriptor.ConstraintFinder findConstraints() {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }

        private boolean isConstrained() {
            return hasConstraints() || isCascaded() || !getGroupConversions().isEmpty() || !getConstrainedContainerElementTypes().isEmpty();
        }
    }

    private abstract static class XmlExecutableDescriptor implements ElementDescriptor.ConstraintFinder {

        private final ExecutableMapping executable;

        private XmlExecutableDescriptor(ExecutableMapping executable) {
            this.executable = executable;
        }

        public String getName() {
            return executable.name();
        }

        public List<ParameterDescriptor> getParameterDescriptors() {
            List<ParameterDescriptor> descriptors = new ArrayList<>(executable.parameters().size());
            Parameter[] sourceParameters = executable.source().getParameters();
            for (int i = 0; i < executable.parameters().size(); i++) {
                descriptors.add(new XmlParameterDescriptor(i, executable.parameters().get(i), sourceParameters[i]));
            }
            return List.copyOf(descriptors);
        }

        public CrossParameterDescriptor getCrossParameterDescriptor() {
            return new XmlCrossParameterDescriptor(executable.crossParameter(), executable.source());
        }

        public ReturnValueDescriptor getReturnValueDescriptor() {
            return new XmlReturnValueDescriptor(executable.returnValue(), executable.source());
        }

        public boolean hasConstrainedParameters() {
            if (getCrossParameterDescriptor().hasConstraints()) {
                return true;
            }
            return getParameterDescriptors().stream()
                .anyMatch(parameter -> parameter.hasConstraints()
                    || parameter.isCascaded()
                    || !parameter.getGroupConversions().isEmpty()
                    || !parameter.getConstrainedContainerElementTypes().isEmpty());
        }

        public boolean hasConstrainedReturnValue() {
            ReturnValueDescriptor descriptor = getReturnValueDescriptor();
            return descriptor.hasConstraints()
                || descriptor.isCascaded()
                || !descriptor.getGroupConversions().isEmpty()
                || !descriptor.getConstrainedContainerElementTypes().isEmpty();
        }

        public boolean hasConstraints() {
            return false;
        }

        public Class<?> getElementClass() {
            return Object.class;
        }

        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return Collections.emptySet();
        }

        public ElementDescriptor.ConstraintFinder findConstraints() {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }
    }

    private static final class XmlMethodDescriptor extends XmlExecutableDescriptor implements MethodDescriptor {

        private XmlMethodDescriptor(ExecutableMapping executable) {
            super(executable);
        }
    }

    private static final class XmlConstructorDescriptor extends XmlExecutableDescriptor implements ConstructorDescriptor {

        private XmlConstructorDescriptor(ExecutableMapping executable) {
            super(executable);
        }
    }

    private record XmlParameterDescriptor(int index, ParameterMapping parameter, Parameter source)
        implements ParameterDescriptor, ElementDescriptor.ConstraintFinder {

        @Override
        public int getIndex() {
            return index;
        }

        @Override
        public String getName() {
            return "arg" + index;
        }

        @Override
        public boolean isCascaded() {
            return parameter.metadata().hasAnnotation(Valid.class)
                || !parameter.annotationsIgnored() && source.isAnnotationPresent(Valid.class);
        }

        @Override
        public Set<GroupConversionDescriptor> getGroupConversions() {
            return groupConversions(parameter.metadata(), source, parameter.annotationsIgnored());
        }

        @Override
        public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
            return containerElementDescriptors(parameter.containerElements());
        }

        @Override
        public boolean hasConstraints() {
            return !getConstraintDescriptors().isEmpty();
        }

        @Override
        public Class<?> getElementClass() {
            return parameter.type();
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return constraintDescriptors(parameter.metadata(), source, parameter.annotationsIgnored(), ConstraintTarget.RETURN_VALUE);
        }

        @Override
        public ElementDescriptor.ConstraintFinder findConstraints() {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }
    }

    private record XmlReturnValueDescriptor(ElementMapping returnValue, Executable source)
        implements ReturnValueDescriptor, ElementDescriptor.ConstraintFinder {

        @Override
        public boolean isCascaded() {
            return returnValue.metadata().hasAnnotation(Valid.class)
                || !returnValue.annotationsIgnored() && source.isAnnotationPresent(Valid.class);
        }

        @Override
        public Set<GroupConversionDescriptor> getGroupConversions() {
            return groupConversions(returnValue.metadata(), source, returnValue.annotationsIgnored());
        }

        @Override
        public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
            return containerElementDescriptors(returnValue.containerElements());
        }

        @Override
        public boolean hasConstraints() {
            return !getConstraintDescriptors().isEmpty();
        }

        @Override
        public Class<?> getElementClass() {
            if (source instanceof Method method) {
                return method.getReturnType();
            }
            return source.getDeclaringClass();
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return constraintDescriptors(returnValue.metadata(), source, returnValue.annotationsIgnored(), ConstraintTarget.RETURN_VALUE);
        }

        @Override
        public ElementDescriptor.ConstraintFinder findConstraints() {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }
    }

    private record XmlCrossParameterDescriptor(ElementMapping crossParameter, Executable source)
        implements CrossParameterDescriptor, ElementDescriptor.ConstraintFinder {

        @Override
        public Class<?> getElementClass() {
            return Object[].class;
        }

        @Override
        public boolean hasConstraints() {
            return !getConstraintDescriptors().isEmpty();
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return constraintDescriptors(crossParameter.metadata(), source, crossParameter.annotationsIgnored(), ConstraintTarget.PARAMETERS);
        }

        @Override
        public ElementDescriptor.ConstraintFinder findConstraints() {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }
    }

    private static final class XmlConstraintDescriptor<A extends Annotation> implements ConstraintDescriptor<A> {

        private final Class<A> type;
        private final AnnotationValue<A> annotationValue;

        private XmlConstraintDescriptor(Class<A> type, AnnotationValue<A> annotationValue) {
            this.type = type;
            this.annotationValue = annotationValue;
        }

        @Override
        public A getAnnotation() {
            Class<A> annotationType = type;
            ClassLoader classLoader = currentClassLoader();
            if (type.getClassLoader() != classLoader) {
                try {
                    Class<?> currentType = Class.forName(type.getName(), false, classLoader);
                    if (Annotation.class.isAssignableFrom(currentType)) {
                        annotationType = (Class<A>) currentType;
                    }
                } catch (ClassNotFoundException e) {
                    // Keep the type resolved from the XML metadata provider.
                }
            }
            return AnnotationMetadataSupport.buildAnnotation(annotationType, annotationValue);
        }

        @Override
        public String getMessageTemplate() {
            return annotationValue.stringValue("message")
                .orElseGet(() -> "{" + type.getName() + ".message}");
        }

        @Override
        public Set<Class<?>> getGroups() {
            Class<?>[] groups = annotationValue.classValues("groups");
            return groups.length == 0 ? Set.of(Default.class) : Set.of(groups);
        }

        @Override
        public Set<Class<? extends Payload>> getPayload() {
            return Set.of((Class<? extends Payload>[]) annotationValue.classValues("payload"));
        }

        @Override
        public ConstraintTarget getValidationAppliesTo() {
            return annotationValue.enumValue("validationAppliesTo", ConstraintTarget.class).orElse(null);
        }

        @Override
        public List<Class<? extends ConstraintValidator<A, ?>>> getConstraintValidatorClasses() {
            Constraint constraint = type.getAnnotation(Constraint.class);
            return constraint == null ? Collections.emptyList() : (List) List.of(constraint.validatedBy());
        }

        @Override
        public Map<String, Object> getAttributes() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            annotationValue.getValues().forEach((key, value) -> attributes.put(key.toString(), value));
            Map<CharSequence, Object> defaultValues = annotationValue.getDefaultValues();
            if (defaultValues != null) {
                defaultValues.forEach((key, value) -> attributes.putIfAbsent(key.toString(), value));
            }
            return Map.copyOf(attributes);
        }

        @Override
        public Set<ConstraintDescriptor<?>> getComposingConstraints() {
            return Collections.emptySet();
        }

        @Override
        public boolean isReportAsSingleViolation() {
            return false;
        }

        @Override
        public ValidateUnwrappedValue getValueUnwrapping() {
            Set<Class<? extends Payload>> payload = getPayload();
            if (payload.contains(Unwrapping.Unwrap.class)) {
                return ValidateUnwrappedValue.UNWRAP;
            }
            if (payload.contains(Unwrapping.Skip.class)) {
                return ValidateUnwrappedValue.SKIP;
            }
            return ValidateUnwrappedValue.DEFAULT;
        }

        @Override
        public <U> U unwrap(Class<U> type) {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new ValidationException("Cannot unwrap " + getClass().getName() + " as " + type.getName());
        }
    }

    private static final class AnnotationConstraintDescriptor<A extends Annotation> implements ConstraintDescriptor<A> {

        private final A annotation;
        private final Class<A> type;

        @SuppressWarnings("unchecked")
        private AnnotationConstraintDescriptor(A annotation) {
            this.annotation = annotation;
            this.type = (Class<A>) annotation.annotationType();
        }

        @Override
        public A getAnnotation() {
            return annotation;
        }

        @Override
        public String getMessageTemplate() {
            return (String) readMember(annotation, "message", "{" + type.getName() + ".message}");
        }

        @Override
        public Set<Class<?>> getGroups() {
            Class<?>[] groups = (Class<?>[]) readMember(annotation, "groups", new Class<?>[0]);
            return groups.length == 0 ? Set.of(Default.class) : Set.of(groups);
        }

        @Override
        public Set<Class<? extends Payload>> getPayload() {
            return Set.of((Class<? extends Payload>[]) readMember(annotation, "payload", new Class<?>[0]));
        }

        @Override
        public ConstraintTarget getValidationAppliesTo() {
            return (ConstraintTarget) readMember(annotation, "validationAppliesTo", null);
        }

        @Override
        public List<Class<? extends ConstraintValidator<A, ?>>> getConstraintValidatorClasses() {
            Constraint constraint = type.getAnnotation(Constraint.class);
            return constraint == null ? Collections.emptyList() : (List) List.of(constraint.validatedBy());
        }

        @Override
        public Map<String, Object> getAttributes() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            for (Method method : type.getDeclaredMethods()) {
                attributes.put(method.getName(), readMember(annotation, method.getName(), method.getDefaultValue()));
            }
            return Map.copyOf(attributes);
        }

        @Override
        public Set<ConstraintDescriptor<?>> getComposingConstraints() {
            return Collections.emptySet();
        }

        @Override
        public boolean isReportAsSingleViolation() {
            return false;
        }

        @Override
        public ValidateUnwrappedValue getValueUnwrapping() {
            Set<Class<? extends Payload>> payload = getPayload();
            if (payload.contains(Unwrapping.Unwrap.class)) {
                return ValidateUnwrappedValue.UNWRAP;
            }
            if (payload.contains(Unwrapping.Skip.class)) {
                return ValidateUnwrappedValue.SKIP;
            }
            return ValidateUnwrappedValue.DEFAULT;
        }

        @Override
        public <U> U unwrap(Class<U> type) {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new ValidationException("Cannot unwrap " + getClass().getName() + " as " + type.getName());
        }

        private static Object readMember(Annotation annotation, String member, Object defaultValue) {
            try {
                return annotation.annotationType().getDeclaredMethod(member).invoke(annotation);
            } catch (NoSuchMethodException e) {
                return defaultValue;
            } catch (ReflectiveOperationException e) {
                throw new ValidationException("Cannot read annotation member " + annotation.annotationType().getName() + "." + member, e);
            }
        }
    }

    private record XmlGroupConversionDescriptor(Class<?> from, Class<?> to) implements GroupConversionDescriptor {

        @Override
        public Class<?> getFrom() {
            return from;
        }

        @Override
        public Class<?> getTo() {
            return to;
        }
    }

    private record XmlContainerElementTypeDescriptor(ContainerElementMapping mapping)
        implements ContainerElementTypeDescriptor, ElementDescriptor.ConstraintFinder {

        @Override
        public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
            return containerElementDescriptors(mapping.containerElements());
        }

        @Override
        public boolean isCascaded() {
            return mapping.metadata().hasAnnotation(Valid.class);
        }

        @Override
        public Set<GroupConversionDescriptor> getGroupConversions() {
            return groupConversions(mapping.metadata());
        }

        @Override
        public Class<?> getContainerClass() {
            return mapping.containerClass();
        }

        @Override
        public Integer getTypeArgumentIndex() {
            return mapping.typeArgumentIndex();
        }

        @Override
        public boolean hasConstraints() {
            return !getConstraintDescriptors().isEmpty();
        }

        @Override
        public Class<?> getElementClass() {
            return mapping.elementClass();
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return constraintDescriptors(mapping.metadata());
        }

        @Override
        public ElementDescriptor.ConstraintFinder findConstraints() {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ElementDescriptor.ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }
    }

    private static Set<ContainerElementTypeDescriptor> containerElementDescriptors(List<ContainerElementMapping> mappings) {
        if (mappings.isEmpty()) {
            return Collections.emptySet();
        }
        return mappings.stream()
            .map(XmlContainerElementTypeDescriptor::new)
            .collect(Collectors.toUnmodifiableSet());
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
                                   java.lang.reflect.AnnotatedElement source,
                                   Class<?> elementClass,
                                   List<ContainerElementMapping> containerElements) {
    }

    private record ExecutableKey(String name, List<Class<?>> parameterTypes) {
    }

    private record ExecutableMapping(String name,
                                     Executable source,
                                     List<ParameterMapping> parameters,
                                     ElementMapping crossParameter,
                                     ElementMapping returnValue) {

        List<Class<?>> parameterTypes() {
            return parameters.stream()
                .map(ParameterMapping::type)
                .toList();
        }

        ExecutableMapping withSource(Executable source) {
            return new ExecutableMapping(name, source, parameters, crossParameter, returnValue);
        }

        ExecutableMapping withContainerElements(Element executableElement,
                                                String defaultPackage,
                                                XmlValidationMetadataProvider provider) {
            Type[] genericParameterTypes = source.getGenericParameterTypes();
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
                            provider.parseContainerElements(element, defaultPackage, genericParameterTypes[parameterIndex])
                        ));
                        parameterIndex++;
                    }
                    case "return-value" -> {
                        Type returnType = source instanceof Method method ? method.getGenericReturnType() : source.getDeclaringClass();
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
                || !groupConversions(metadata).isEmpty()
                || !containerElements.isEmpty();
        }
    }
}
