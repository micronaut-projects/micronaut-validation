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
package io.micronaut.validation.reflection;

import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.ContainerElementNodeBuilderCustomizableContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.ContainerElementNodeBuilderDefinedContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.ContainerElementNodeContextBuilder;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.LeafNodeBuilderCustomizableContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.LeafNodeBuilderDefinedContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.LeafNodeContextBuilder;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderDefinedContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.NodeContextBuilder;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.ValidationException;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Reflection-backed constraint validator context.
 *
 * @since 5.1
 */
final class ReflectionConstraintValidatorContext implements ConstraintValidatorContext {

    private final ClockProvider clockProvider;
    @Nullable
    private final Object rootBean;
    private final String defaultMessageTemplate;
    private final Path basePath;
    private final List<CustomViolation> customViolations = new ArrayList<>();
    private boolean defaultViolationDisabled;

    ReflectionConstraintValidatorContext(ClockProvider clockProvider,
                                         @Nullable Object rootBean,
                                         String defaultMessageTemplate,
                                         Path basePath) {
        this.clockProvider = clockProvider;
        this.rootBean = rootBean;
        this.defaultMessageTemplate = defaultMessageTemplate;
        this.basePath = basePath;
    }

    @Override
    public void disableDefaultConstraintViolation() {
        defaultViolationDisabled = true;
    }

    @Override
    public String getDefaultConstraintMessageTemplate() {
        return defaultMessageTemplate;
    }

    @Override
    public ClockProvider getClockProvider() {
        return clockProvider;
    }

    @Override
    public ConstraintViolationBuilder buildConstraintViolationWithTemplate(String messageTemplate) {
        return new SimpleConstraintViolationBuilder(this, messageTemplate, new ArrayList<>());
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new ValidationException("Cannot unwrap " + getClass().getName() + " as " + type.getName());
    }

    @Override
    public @Nullable Object getRootBean() {
        return rootBean;
    }

    boolean defaultViolationDisabled() {
        return defaultViolationDisabled;
    }

    List<CustomViolation> customViolations() {
        return customViolations;
    }

    private void addCustomViolation(String messageTemplate, List<ReflectionNode> nodes) {
        customViolations.add(new CustomViolation(messageTemplate, new ReflectionCustomPath(basePath, nodes)));
    }

    private static void replaceLast(List<ReflectionNode> nodes,
                                    @Nullable Boolean inIterable,
                                    @Nullable Object key,
                                    @Nullable Integer index,
                                    @Nullable Class<?> containerClass,
                                    @Nullable Integer typeArgumentIndex) {
        if (nodes.isEmpty()) {
            return;
        }
        int lastIndex = nodes.size() - 1;
        ReflectionNode last = nodes.get(lastIndex);
        nodes.set(lastIndex, new ReflectionNode(
            last.name(),
            inIterable == null ? last.inIterable() : inIterable,
            key == null ? last.key() : key,
            index == null ? last.index() : index,
            containerClass == null ? last.containerClass() : containerClass,
            typeArgumentIndex == null ? last.typeArgumentIndex() : typeArgumentIndex
        ));
    }

    record CustomViolation(
        String messageTemplate,
        Path path
    ) {
    }

    private record SimpleConstraintViolationBuilder(
        ReflectionConstraintValidatorContext context,
        String messageTemplate,
        List<ReflectionNode> nodes
    ) implements ConstraintViolationBuilder {

        @Override
        public NodeBuilderDefinedContext addNode(String name) {
            nodes.add(new ReflectionNode(name));
            return new SimpleNodeBuilder(context, messageTemplate, nodes);
        }

        @Override
        public NodeBuilderCustomizableContext addPropertyNode(String name) {
            nodes.add(new ReflectionNode(name));
            return new SimpleNodeBuilder(context, messageTemplate, nodes);
        }

        @Override
        public LeafNodeBuilderCustomizableContext addBeanNode() {
            return new SimpleLeafNodeBuilder(context, messageTemplate, nodes);
        }

        @Override
        public ContainerElementNodeBuilderCustomizableContext addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
            nodes.add(new ReflectionNode(name, false, null, null, containerType, typeArgumentIndex));
            return new SimpleContainerElementNodeBuilder(context, messageTemplate, nodes);
        }

        @Override
        public NodeBuilderDefinedContext addParameterNode(int index) {
            return new SimpleNodeBuilder(context, messageTemplate, nodes);
        }

        @Override
        public ConstraintValidatorContext addConstraintViolation() {
            context.addCustomViolation(messageTemplate, nodes);
            return context;
        }
    }

    private record SimpleNodeBuilder(
        ReflectionConstraintValidatorContext context,
        String messageTemplate,
        List<ReflectionNode> nodes
    ) implements ConstraintViolationBuilder.NodeBuilderDefinedContext,
        ConstraintViolationBuilder.NodeBuilderCustomizableContext,
        ConstraintViolationBuilder.NodeContextBuilder {

        @Override
        public NodeBuilderCustomizableContext addNode(String name) {
            nodes.add(new ReflectionNode(name));
            return this;
        }

        @Override
        public NodeBuilderCustomizableContext addPropertyNode(String name) {
            nodes.add(new ReflectionNode(name));
            return this;
        }

        @Override
        public LeafNodeBuilderCustomizableContext addBeanNode() {
            return new SimpleLeafNodeBuilder(context, messageTemplate, nodes);
        }

        @Override
        public ContainerElementNodeBuilderCustomizableContext addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
            nodes.add(new ReflectionNode(name, false, null, null, containerType, typeArgumentIndex));
            return new SimpleContainerElementNodeBuilder(context, messageTemplate, nodes);
        }

        @Override
        public NodeContextBuilder inIterable() {
            replaceLast(nodes, true, null, null, null, null);
            return this;
        }

        @Override
        public NodeBuilderCustomizableContext inContainer(Class<?> containerType, Integer typeArgumentIndex) {
            replaceLast(nodes, null, null, null, containerType, typeArgumentIndex);
            return this;
        }

        @Override
        public NodeBuilderDefinedContext atKey(Object key) {
            replaceLast(nodes, null, key, null, null, null);
            return this;
        }

        @Override
        public NodeBuilderDefinedContext atIndex(Integer index) {
            replaceLast(nodes, null, null, index, null, null);
            return this;
        }

        @Override
        public ConstraintValidatorContext addConstraintViolation() {
            context.addCustomViolation(messageTemplate, nodes);
            return context;
        }
    }

    private record SimpleLeafNodeBuilder(
        ReflectionConstraintValidatorContext context,
        String messageTemplate,
        List<ReflectionNode> nodes
    ) implements ConstraintViolationBuilder.LeafNodeBuilderCustomizableContext,
        ConstraintViolationBuilder.LeafNodeContextBuilder,
        ConstraintViolationBuilder.LeafNodeBuilderDefinedContext {

        @Override
        public LeafNodeContextBuilder inIterable() {
            replaceLast(nodes, true, null, null, null, null);
            return this;
        }

        @Override
        public LeafNodeBuilderCustomizableContext inContainer(Class<?> containerType, Integer typeArgumentIndex) {
            replaceLast(nodes, null, null, null, containerType, typeArgumentIndex);
            return this;
        }

        @Override
        public LeafNodeBuilderDefinedContext atKey(Object key) {
            replaceLast(nodes, null, key, null, null, null);
            return this;
        }

        @Override
        public LeafNodeBuilderDefinedContext atIndex(Integer index) {
            replaceLast(nodes, null, null, index, null, null);
            return this;
        }

        @Override
        public ConstraintValidatorContext addConstraintViolation() {
            context.addCustomViolation(messageTemplate, nodes);
            return context;
        }
    }

    private record SimpleContainerElementNodeBuilder(
        ReflectionConstraintValidatorContext context,
        String messageTemplate,
        List<ReflectionNode> nodes
    ) implements ConstraintViolationBuilder.ContainerElementNodeBuilderCustomizableContext,
        ConstraintViolationBuilder.ContainerElementNodeContextBuilder,
        ConstraintViolationBuilder.ContainerElementNodeBuilderDefinedContext {

        @Override
        public ContainerElementNodeContextBuilder inIterable() {
            replaceLast(nodes, true, null, null, null, null);
            return this;
        }

        @Override
        public NodeBuilderCustomizableContext addPropertyNode(String name) {
            nodes.add(new ReflectionNode(name));
            return new SimpleNodeBuilder(context, messageTemplate, nodes);
        }

        @Override
        public LeafNodeBuilderCustomizableContext addBeanNode() {
            return new SimpleLeafNodeBuilder(context, messageTemplate, nodes);
        }

        @Override
        public ContainerElementNodeBuilderCustomizableContext addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
            nodes.add(new ReflectionNode(name, false, null, null, containerType, typeArgumentIndex));
            return this;
        }

        @Override
        public ContainerElementNodeBuilderDefinedContext atKey(Object key) {
            replaceLast(nodes, null, key, null, null, null);
            return this;
        }

        @Override
        public ContainerElementNodeBuilderDefinedContext atIndex(Integer index) {
            replaceLast(nodes, null, null, index, null, null);
            return this;
        }

        @Override
        public ConstraintValidatorContext addConstraintViolation() {
            context.addCustomViolation(messageTemplate, nodes);
            return context;
        }
    }

    private record ReflectionCustomPath(Path basePath, List<ReflectionNode> nodes) implements Path {

        @Override
        public Iterator<Node> iterator() {
            List<Node> pathNodes = new ArrayList<>();
            for (Node node : basePath) {
                if (node.getKind() != ElementKind.BEAN || nodes.isEmpty()) {
                    pathNodes.add(node);
                }
            }
            pathNodes.addAll(nodes);
            return pathNodes.iterator();
        }

        @Override
        public String toString() {
            List<String> parts = new ArrayList<>();
            for (Node node : this) {
                parts.add(node.toString());
            }
            return String.join(".", parts);
        }
    }

    private record ReflectionNode(@Nullable String name,
                                  boolean inIterable,
                                  @Nullable Object key,
                                  @Nullable Integer index,
                                  @Nullable Class<?> containerClass,
                                  @Nullable Integer typeArgumentIndex) implements Path.PropertyNode {

        private ReflectionNode(@Nullable String name) {
            this(name, false, null, null, null, null);
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.PROPERTY;
        }

        @Override
        public boolean isInIterable() {
            return inIterable;
        }

        @Override
        public Integer getIndex() {
            return index;
        }

        @Override
        public Object getKey() {
            return key;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public <T extends Path.Node> T as(Class<T> nodeType) {
            return nodeType.cast(this);
        }

        @Override
        public Class<?> getContainerClass() {
            return containerClass;
        }

        @Override
        public Integer getTypeArgumentIndex() {
            return typeArgumentIndex;
        }

        @Override
        public String toString() {
            return name == null ? "" : name;
        }
    }
}
