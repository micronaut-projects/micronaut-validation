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
 * <p>This is the Jakarta-facing context used only by validators instantiated by
 * the optional reflection fallback. Maintainers should keep path-building
 * behavior here aligned with the violation path types produced by
 * {@link ReflectionValidator}.</p>
 *
 * @since 5.1
 */
final class ReflectionConstraintValidatorContext implements ConstraintValidatorContext {

    private final ClockProvider clockProvider;
    @Nullable
    private final Object rootBean;
    private final String defaultMessageTemplate;
    private final Path basePath;
    private final List<String> parameterNames;
    private final List<CustomViolation> customViolations = new ArrayList<>();
    private boolean defaultViolationDisabled;

    /**
     * Creates a context for property or bean validation.
     *
     * @param clockProvider The active validation clock provider
     * @param rootBean The root bean, if available
     * @param defaultMessageTemplate The default message template for the current
     * constraint
     * @param basePath The path to the constrained element
     */
    ReflectionConstraintValidatorContext(ClockProvider clockProvider,
                                         @Nullable Object rootBean,
                                         String defaultMessageTemplate,
                                         Path basePath) {
        this(clockProvider, rootBean, defaultMessageTemplate, basePath, List.of());
    }

    /**
     * Creates a context for executable validation where parameter names may be
     * needed for custom violation paths.
     *
     * @param clockProvider The active validation clock provider
     * @param rootBean The root bean, if available
     * @param defaultMessageTemplate The default message template for the current
     * constraint
     * @param basePath The path to the constrained executable element
     * @param parameterNames Parameter names resolved for the executable
     */
    ReflectionConstraintValidatorContext(ClockProvider clockProvider,
                                         @Nullable Object rootBean,
                                         String defaultMessageTemplate,
                                         Path basePath,
                                         List<String> parameterNames) {
        this.clockProvider = clockProvider;
        this.rootBean = rootBean;
        this.defaultMessageTemplate = defaultMessageTemplate;
        this.basePath = basePath;
        this.parameterNames = parameterNames;
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

    /**
     * @return Whether the validator disabled the default violation
     */
    boolean defaultViolationDisabled() {
        return defaultViolationDisabled;
    }

    /**
     * @return Custom violations added through the Jakarta builder API
     */
    List<CustomViolation> customViolations() {
        return customViolations;
    }

    private void addCustomViolation(String messageTemplate, List<Path.Node> nodes) {
        customViolations.add(new CustomViolation(messageTemplate, new ReflectionCustomPath(basePath, nodes)));
    }

    private boolean hasCrossParameterBasePath() {
        for (Path.Node node : basePath) {
            if (node.getKind() == ElementKind.CROSS_PARAMETER) {
                return true;
            }
        }
        return false;
    }

    private static void replaceLast(List<Path.Node> nodes,
                                    @Nullable Boolean inIterable,
                                    @Nullable Object key,
                                    @Nullable Integer index,
                                    @Nullable Class<?> containerClass,
                                    @Nullable Integer typeArgumentIndex) {
        if (nodes.isEmpty()) {
            return;
        }
        int lastIndex = nodes.size() - 1;
        Path.Node replacement = replacementNode(nodes.get(lastIndex), inIterable, key, index, containerClass, typeArgumentIndex);
        if (replacement == null) {
            return;
        }
        nodes.set(lastIndex, replacement);
    }

    private static Path.@Nullable Node replacementNode(Path.Node node,
                                                       @Nullable Boolean inIterable,
                                                       @Nullable Object key,
                                                       @Nullable Integer index,
                                                       @Nullable Class<?> containerClass,
                                                       @Nullable Integer typeArgumentIndex) {
        if (node instanceof ReflectionBeanNode last) {
            return new ReflectionBeanNode(
                inIterable == null ? last.inIterable() : inIterable,
                key == null ? last.key() : key,
                index == null ? last.index() : index,
                containerClass == null ? last.containerClass() : containerClass,
                typeArgumentIndex == null ? last.typeArgumentIndex() : typeArgumentIndex
            );
        }
        if (!(node instanceof ReflectionNode last)) {
            return null;
        }
        return new ReflectionNode(
            last.kind(),
            last.name(),
            inIterable == null ? last.inIterable() : inIterable,
            key == null ? last.key() : key,
            index == null ? last.index() : index,
            containerClass == null ? last.containerClass() : containerClass,
            typeArgumentIndex == null ? last.typeArgumentIndex() : typeArgumentIndex
        );
    }

    /**
     * Custom violation captured from the Jakarta builder API before
     * {@link ReflectionValidator} turns it into a constraint violation.
     *
     * @param messageTemplate The message template supplied by the validator
     * @param path The custom violation path
     */
    record CustomViolation(
        String messageTemplate,
        Path path
    ) {
    }

    private record SimpleConstraintViolationBuilder(
        ReflectionConstraintValidatorContext context,
        String messageTemplate,
        List<Path.Node> nodes
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
            nodes.add(new ReflectionBeanNode());
            return new SimpleLeafNodeBuilder(context, messageTemplate, nodes);
        }

        @Override
        public ContainerElementNodeBuilderCustomizableContext addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
            nodes.add(new ReflectionNode(ElementKind.CONTAINER_ELEMENT, name, false, null, null, containerType, typeArgumentIndex));
            return new SimpleContainerElementNodeBuilder(context, messageTemplate, nodes);
        }

        @Override
        public NodeBuilderDefinedContext addParameterNode(int index) {
            if (!context.hasCrossParameterBasePath()) {
                throw new IllegalStateException("Parameter nodes can only be added from cross-parameter constraints");
            }
            String name = index >= 0 && context.parameterNames.size() > index ? context.parameterNames.get(index) : null;
            nodes.add(new ReflectionParameterNode(name, index));
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
        List<Path.Node> nodes
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
            nodes.add(new ReflectionBeanNode());
            return new SimpleLeafNodeBuilder(context, messageTemplate, nodes);
        }

        @Override
        public ContainerElementNodeBuilderCustomizableContext addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
            nodes.add(new ReflectionNode(ElementKind.CONTAINER_ELEMENT, name, false, null, null, containerType, typeArgumentIndex));
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
        List<Path.Node> nodes
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
        List<Path.Node> nodes
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
            nodes.add(new ReflectionBeanNode());
            return new SimpleLeafNodeBuilder(context, messageTemplate, nodes);
        }

        @Override
        public ContainerElementNodeBuilderCustomizableContext addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
            nodes.add(new ReflectionNode(ElementKind.CONTAINER_ELEMENT, name, false, null, null, containerType, typeArgumentIndex));
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

    private record ReflectionCustomPath(Path basePath, List<Path.Node> nodes) implements Path {

        @Override
        public Iterator<Node> iterator() {
            List<Node> pathNodes = new ArrayList<>();
            @Nullable
            Node inheritedContainerNode = null;
            for (Node node : basePath) {
                if (nodes.isEmpty()) {
                    pathNodes.add(node);
                } else if (node.getKind() == ElementKind.BEAN) {
                    inheritedContainerNode = node;
                } else if (node.getKind() != ElementKind.CROSS_PARAMETER) {
                    pathNodes.add(node);
                }
            }
            if (inheritedContainerNode != null && !nodes.isEmpty()) {
                pathNodes.add(withContainerContext(nodes.get(0), inheritedContainerNode));
                pathNodes.addAll(nodes.subList(1, nodes.size()));
            } else {
                pathNodes.addAll(nodes);
            }
            return pathNodes.iterator();
        }

        private static Node withContainerContext(Node node, Node containerNode) {
            if (node instanceof ReflectionNode reflectionNode) {
                return new ReflectionNode(
                    reflectionNode.kind(),
                    reflectionNode.name(),
                    reflectionNode.inIterable() || containerNode.isInIterable(),
                    reflectionNode.key() == null ? containerNode.getKey() : reflectionNode.key(),
                    reflectionNode.index() == null ? containerNode.getIndex() : reflectionNode.index(),
                    reflectionNode.containerClass() == null ? containerNode.as(Path.BeanNode.class).getContainerClass() : reflectionNode.containerClass(),
                    reflectionNode.typeArgumentIndex() == null ? containerNode.as(Path.BeanNode.class).getTypeArgumentIndex() : reflectionNode.typeArgumentIndex()
                );
            }
            if (node instanceof ReflectionBeanNode) {
                return new ReflectionBeanNode(
                    containerNode.isInIterable(),
                    containerNode.getKey(),
                    containerNode.getIndex(),
                    containerNode.as(Path.BeanNode.class).getContainerClass(),
                    containerNode.as(Path.BeanNode.class).getTypeArgumentIndex()
                );
            }
            return node;
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

    private record ReflectionParameterNode(@Nullable String name, int parameterIndex) implements Path.ParameterNode {

        @Override
        public ElementKind getKind() {
            return ElementKind.PARAMETER;
        }

        @Override
        @Nullable
        public String getName() {
            return name;
        }

        @Override
        public boolean isInIterable() {
            return false;
        }

        @Override
        @Nullable
        public Integer getIndex() {
            return null;
        }

        @Override
        @Nullable
        public Object getKey() {
            return null;
        }

        @Override
        public <T extends Path.Node> T as(Class<T> nodeType) {
            return nodeType.cast(this);
        }

        @Override
        public int getParameterIndex() {
            return parameterIndex;
        }
    }

    private record ReflectionBeanNode(
        boolean inIterable,
        @Nullable Object key,
        @Nullable Integer index,
        @Nullable Class<?> containerClass,
        @Nullable Integer typeArgumentIndex
    ) implements Path.BeanNode {

        private ReflectionBeanNode() {
            this(false, null, null, null, null);
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.BEAN;
        }

        @Override
        @Nullable
        public String getName() {
            return null;
        }

        @Override
        public boolean isInIterable() {
            return inIterable;
        }

        @Override
        @Nullable
        public Integer getIndex() {
            return index;
        }

        @Override
        @Nullable
        public Object getKey() {
            return key;
        }

        @Override
        public <T extends Path.Node> T as(Class<T> nodeType) {
            return nodeType.cast(this);
        }

        @Override
        @Nullable
        public Class<?> getContainerClass() {
            return containerClass;
        }

        @Override
        @Nullable
        public Integer getTypeArgumentIndex() {
            return typeArgumentIndex;
        }
    }

    private record ReflectionNode(ElementKind kind,
                                  @Nullable String name,
                                  boolean inIterable,
                                  @Nullable Object key,
                                  @Nullable Integer index,
                                  @Nullable Class<?> containerClass,
                                  @Nullable Integer typeArgumentIndex) implements Path.PropertyNode, Path.ContainerElementNode {

        private ReflectionNode(@Nullable String name) {
            this(ElementKind.PROPERTY, name, false, null, null, null, null);
        }

        @Override
        public ElementKind getKind() {
            return kind;
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
