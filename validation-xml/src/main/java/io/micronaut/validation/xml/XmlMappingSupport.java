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

import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XML structure checks and DOM access shared by validation XML readers.
 *
 * @since 5.3.0
 */
final class XmlMappingSupport {

    private static final Map<String, Optional<Schema>> SCHEMAS = new ConcurrentHashMap<>();

    private static final Set<String> CONSTRAINED_ELEMENT_CHILDREN = Set.of("valid", "convert-group", "container-element-type", "constraint");
    private static final Set<String> EXECUTABLE_CHILDREN = Set.of("parameter", "cross-parameter", "return-value");
    private static final Set<String> VALUES = Set.of("value");

    /** The elements the constraint mapping schema allows in each element; one not listed has no child elements. */
    private static final Map<String, Set<String>> MAPPING_CHILDREN = Map.ofEntries(
        Map.entry("constraint-mappings", Set.of("default-package", "bean", "constraint-definition")),
        Map.entry("bean", Set.of("class", "field", "getter", "constructor", "method")),
        Map.entry("class", Set.of("group-sequence", "constraint")),
        Map.entry("field", CONSTRAINED_ELEMENT_CHILDREN),
        Map.entry("getter", CONSTRAINED_ELEMENT_CHILDREN),
        Map.entry("parameter", CONSTRAINED_ELEMENT_CHILDREN),
        Map.entry("return-value", CONSTRAINED_ELEMENT_CHILDREN),
        Map.entry("container-element-type", CONSTRAINED_ELEMENT_CHILDREN),
        Map.entry("cross-parameter", Set.of("constraint")),
        Map.entry("constructor", EXECUTABLE_CHILDREN),
        Map.entry("method", EXECUTABLE_CHILDREN),
        Map.entry("constraint", Set.of("message", "groups", "payload", "element")),
        Map.entry("element", Set.of("value", "annotation")),
        Map.entry("annotation", Set.of("element")),
        Map.entry("groups", VALUES),
        Map.entry("payload", VALUES),
        Map.entry("group-sequence", VALUES),
        Map.entry("constraint-definition", Set.of("validated-by")),
        Map.entry("validated-by", VALUES)
    );

    private XmlMappingSupport() { }

    static String requireAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        if (value.isBlank()) {
            throw new ValidationException("Missing required validation XML attribute " + name + " on " + localName(element));
        }
        return value;
    }

    @Nullable
    static Element child(Element parent, String name) {
        List<Element> children = children(parent, name);
        return children.isEmpty() ? null : children.get(0);
    }

    static List<Element> children(Element parent, String name) {
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

    static String textOfChild(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? "" : text(child);
    }

    static String singleValue(Element element) {
        Element value = child(element, "value");
        return value == null ? text(element) : text(value);
    }

    static boolean booleanAttribute(Element element, String name, boolean defaultValue) {
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
    /**
     * Validates a parsed document against the schema of its version, which the Jakarta Validation API ships: a wrong
     * root, a repeated singleton element or a malformed value is rejected instead of ignored.
     *
     * @param document The parsed document
     * @param schemaName The schema name without its version, such as {@code validation-configuration}
     * @param resourceDescription Description used in validation errors
     */
    static void validateSchema(Document document, String schemaName, String resourceDescription) {
        String version = document.getDocumentElement().getAttribute("version");
        String resource = schemaName + "-" + (version.isBlank() ? "1.0" : version) + ".xsd";
        Schema schema = SCHEMAS.computeIfAbsent(resource, XmlMappingSupport::loadSchema).orElse(null);
        if (schema == null) {
            return;
        }
        try {
            javax.xml.validation.Validator validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document validated = document;
            if ("3.1".equals(version)) {
                // the 3.1 schemas the API ships fix the version at 3.0; the documents are otherwise the same
                validated = (Document) document.cloneNode(true);
                validated.getDocumentElement().setAttribute("version", "3.0");
            }
            validator.validate(new DOMSource(validated));
        } catch (SAXException | IOException e) {
            throw new ValidationException("Invalid " + resourceDescription + ": " + e.getMessage(), e);
        }
    }

    private static Optional<Schema> loadSchema(String resource) {
        URL url = Validation.class.getClassLoader() == null ? null : Validation.class.getClassLoader().getResource(resource);
        if (url == null) {
            return Optional.empty();
        }
        try {
            SchemaFactory factory = SchemaFactory.newDefaultInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return Optional.of(factory.newSchema(url));
        } catch (SAXException e) {
            throw new ValidationException("Cannot load the schema " + resource, e);
        }
    }

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

    /**
     * Rejects an element the mapping schema does not allow where it stands, at any depth: a misspelt or
     * misplaced element would otherwise be skipped, and the constraints it declares silently not applied.
     *
     * @param element The element whose children are checked, down to the leaves
     */
    static void validateMappingStructure(Element element) {
        Set<String> allowed = MAPPING_CHILDREN.getOrDefault(localName(element), Set.of());
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                if (!allowed.contains(localName(child))) {
                    throw new ValidationException("Unsupported constraint mapping XML element: " + localName(child)
                        + " in " + localName(element));
                }
                validateMappingStructure(child);
            }
        }
    }

    static String localName(Element element) {
        String localName = element.getLocalName();
        return localName == null ? element.getTagName() : localName;
    }

    static String text(Element element) {
        return element.getTextContent().trim();
    }

    static String simpleName(Class<?> type) {
        String name = type.getName();
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }

}
