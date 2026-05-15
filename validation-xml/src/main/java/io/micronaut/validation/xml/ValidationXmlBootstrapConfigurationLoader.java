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

import io.micronaut.validation.bootstrap.BootstrapConfigurationLoader;
import io.micronaut.validation.bootstrap.DefaultBootstrapConfiguration;
import jakarta.validation.BootstrapConfiguration;
import jakarta.validation.ValidationException;
import jakarta.validation.executable.ExecutableType;
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
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads {@code META-INF/validation.xml} using JDK XML APIs.
 *
 * @since 5.1
 */
public final class ValidationXmlBootstrapConfigurationLoader implements BootstrapConfigurationLoader {

    private static final String VALIDATION_XML = "META-INF/validation.xml";

    @Override
    public Optional<BootstrapConfiguration> load(ClassLoader classLoader) {
        try {
            Set<URL> resources = new LinkedHashSet<>(Collections.list(classLoader.getResources(VALIDATION_XML)));
            if (resources.isEmpty()) {
                return Optional.empty();
            }
            if (resources.size() > 1) {
                throw new ValidationException("Multiple META-INF/validation.xml resources found: " + resources);
            }
            try (InputStream inputStream = resources.iterator().next().openStream()) {
                return Optional.of(parse(inputStream));
            }
        } catch (IOException e) {
            throw new ValidationException("Cannot read " + VALIDATION_XML, e);
        }
    }

    /**
     * Parses a validation XML stream.
     *
     * @param inputStream The input stream
     * @return The bootstrap configuration
     */
    public BootstrapConfiguration parse(InputStream inputStream) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document document = factory.newDocumentBuilder().parse(inputStream);
            Element root = document.getDocumentElement();
            Map<String, String> properties = new LinkedHashMap<>();
            Set<String> valueExtractors = new LinkedHashSet<>();
            Set<String> constraintMappings = new LinkedHashSet<>();
            Set<ExecutableType> executableTypes = new LinkedHashSet<>();
            boolean executableValidationEnabled = true;
            String defaultProvider = null;
            String constraintValidatorFactory = null;
            String messageInterpolator = null;
            String traversableResolver = null;
            String parameterNameProvider = null;
            String clockProvider = null;

            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (!(node instanceof Element element)) {
                    continue;
                }
                switch (localName(element)) {
                    case "default-provider" -> defaultProvider = text(element);
                    case "constraint-validator-factory" -> constraintValidatorFactory = text(element);
                    case "message-interpolator" -> messageInterpolator = text(element);
                    case "traversable-resolver" -> traversableResolver = text(element);
                    case "parameter-name-provider" -> parameterNameProvider = text(element);
                    case "clock-provider" -> clockProvider = text(element);
                    case "value-extractor" -> valueExtractors.add(text(element));
                    case "constraint-mapping" -> constraintMappings.add(text(element));
                    case "property" -> properties.put(element.getAttribute("name"), text(element));
                    case "executable-validation" -> {
                        if (element.hasAttribute("enabled")) {
                            executableValidationEnabled = Boolean.parseBoolean(element.getAttribute("enabled"));
                        }
                        executableTypes.addAll(executableTypes(element));
                    }
                    default -> {
                    }
                }
            }
            if (executableTypes.isEmpty()) {
                executableTypes.add(ExecutableType.CONSTRUCTORS);
                executableTypes.add(ExecutableType.NON_GETTER_METHODS);
            }
            executableTypes = normalizeExecutableTypes(executableTypes);
            return new DefaultBootstrapConfiguration(
                defaultProvider,
                constraintValidatorFactory,
                messageInterpolator,
                traversableResolver,
                parameterNameProvider,
                clockProvider,
                Set.copyOf(valueExtractors),
                Set.copyOf(constraintMappings),
                executableValidationEnabled,
                Set.copyOf(executableTypes),
                Map.copyOf(properties)
            );
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new ValidationException("Cannot parse " + VALIDATION_XML, e);
        }
    }

    private static Set<ExecutableType> normalizeExecutableTypes(Set<ExecutableType> executableTypes) {
        if (executableTypes.contains(ExecutableType.ALL)) {
            return Set.of(ExecutableType.CONSTRUCTORS, ExecutableType.GETTER_METHODS, ExecutableType.NON_GETTER_METHODS);
        }
        executableTypes.remove(ExecutableType.NONE);
        if (executableTypes.isEmpty()) {
            throw new ValidationException("At least one executable type must be configured");
        }
        return Set.copyOf(executableTypes);
    }

    private static Set<ExecutableType> executableTypes(Element executableValidation) {
        Set<ExecutableType> executableTypes = new LinkedHashSet<>();
        NodeList children = executableValidation.getChildNodes();
        boolean defaultTypesConfigured = false;
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element) || !"default-validated-executable-types".equals(localName(element))) {
                continue;
            }
            defaultTypesConfigured = true;
            NodeList typeNodes = element.getChildNodes();
            for (int j = 0; j < typeNodes.getLength(); j++) {
                Node typeNode = typeNodes.item(j);
                if (typeNode instanceof Element typeElement && "executable-type".equals(localName(typeElement))) {
                    executableTypes.add(ExecutableType.valueOf(text(typeElement).replace('-', '_').toUpperCase(Locale.ROOT)));
                }
            }
        }
        if (defaultTypesConfigured && executableTypes.isEmpty()) {
            throw new ValidationException("At least one executable type must be configured");
        }
        return executableTypes;
    }

    private static String localName(Element element) {
        String localName = element.getLocalName();
        return localName == null ? element.getTagName() : localName;
    }

    private static String text(Element element) {
        return element.getTextContent().trim();
    }
}
