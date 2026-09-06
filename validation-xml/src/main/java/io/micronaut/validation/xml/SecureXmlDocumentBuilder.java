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

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Internal secure DOM parser setup for Jakarta Validation XML documents.
 *
 * <p>Maintainers should route every XML document parsed by this module through
 * this helper so validation configuration and constraint mappings share the
 * same XXE, DTD, schema access, and XInclude protections.</p>
 *
 * @since 5.1
 */
final class SecureXmlDocumentBuilder {

    private SecureXmlDocumentBuilder() {
    }

    /**
     * Parses one Jakarta Validation XML resource with the module's hardened DOM
     * configuration.
     *
     * @param inputStream The XML resource stream. The caller remains responsible
     * for closing streams when ownership matters.
     * @return The parsed DOM document
     * @throws ParserConfigurationException If the JDK parser rejects the secure
     * configuration
     * @throws IOException If the stream cannot be read
     * @throws SAXException If the XML is malformed or rejected by the secure
     * parser settings
     */
    static Document parse(InputStream inputStream) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(inputStream);
    }
}
