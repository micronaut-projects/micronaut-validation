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
import io.micronaut.core.type.Argument;
import io.micronaut.validation.validator.metadata.ValidationDeclaration;
import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import jakarta.validation.groups.ConvertGroup;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.micronaut.validation.xml.XmlMappingSupport.localName;

/** Parsed constraint mappings shared by the parser and metadata adapter. */
final class XmlMapping {
    private XmlMapping() { }

    record BeanMapping(AnnotationMetadata classMetadata,
                       boolean beanAnnotationsIgnored,
                       boolean classAnnotationsIgnored,
                       Map<String, PropertyMapping> properties,
                       Map<ExecutableKey, ExecutableMapping> methods,
                       Map<ExecutableKey, ExecutableMapping> constructors) {
    }

    record ConstraintDefinition(List<Class<?>> validatorClasses,
                                boolean includeExistingValidators) {
    }

    record PropertyMapping(AnnotationMetadata metadata,
                           boolean annotationsIgnored,
                           ValidationDeclaration source,
                           Class<?> elementClass,
                           List<ContainerElementMapping> containerElements) {
    }

    record ExecutableKey(String name, List<Class<?>> parameterTypes) {
    }

    record ExecutableMapping(String name,
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
                                                XmlMappingParser provider) {
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

    record ParameterMapping(Class<?> type,
                            AnnotationMetadata metadata,
                            boolean annotationsIgnored,
                            List<ContainerElementMapping> containerElements) {

        ParameterMapping withContainerElements(List<ContainerElementMapping> containerElements) {
            return new ParameterMapping(type, metadata, annotationsIgnored, containerElements);
        }
    }

    record ElementMapping(AnnotationMetadata metadata,
                          boolean annotationsIgnored,
                          List<ContainerElementMapping> containerElements) {

        static ElementMapping empty(boolean annotationsIgnored) {
            return new ElementMapping(AnnotationMetadata.EMPTY_METADATA, annotationsIgnored, List.of());
        }

        ElementMapping withContainerElements(List<ContainerElementMapping> containerElements) {
            return new ElementMapping(metadata, annotationsIgnored, containerElements);
        }
    }

    record ContainerElementMapping(Class<?> containerClass,
                                   int typeArgumentIndex,
                                   Class<?> elementClass,
                                   AnnotationMetadata metadata,
                                   List<ContainerElementMapping> containerElements) {

        boolean isConstrained() {
            return metadata.hasStereotype(Constraint.class)
                || metadata.hasAnnotation(Valid.class)
                || !metadata.getAnnotationValuesByType(ConvertGroup.class).isEmpty()
                || !containerElements.isEmpty();
        }
    }
}
