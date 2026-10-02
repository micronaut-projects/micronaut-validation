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

import jakarta.validation.ValidationException;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** XML structure checks and DOM access shared by validation XML readers. */
final class XmlMappingSupport {
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
