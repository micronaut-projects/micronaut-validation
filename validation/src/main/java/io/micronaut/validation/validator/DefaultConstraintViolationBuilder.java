/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.validation.validator;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.validation.validator.messages.DefaultMessageInterpolatorContext;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ElementKind;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.Path;

/**
 * The default implementation {@link ConstraintValidatorContext.ConstraintViolationBuilder}.
 *
 * @param <R> The result type
 * @author Denis Stepnov
 */
@Internal
final class DefaultConstraintViolationBuilder<R> implements ConstraintValidatorContext.ConstraintViolationBuilder {

    private final String messageTemplate;
    private final DefaultConstraintValidatorContext<R> constraintValidatorContext;
    private final MessageInterpolator messageInterpolator;
    private final ValidationPath validationPath;
    private final ContainerElementNodeBuilderDefinedContext nodeBuilderDefinedContext = new DefaultContainerElementNodeBuilderDefinedContext();

    @Nullable
    private ValidationPath.MutableContainerContext next;

    DefaultConstraintViolationBuilder(String messageTemplate,
                                      DefaultConstraintValidatorContext<R> constraintValidatorContext,
                                      MessageInterpolator messageInterpolator) {
        this.messageTemplate = messageTemplate;
        this.constraintValidatorContext = constraintValidatorContext;
        this.messageInterpolator = messageInterpolator;
        this.validationPath = new ValidationPath(constraintValidatorContext.getCurrentPath());
        Path.Node last = validationPath.nodes.peekLast();
        ElementKind kind = last == null ? null : last.getKind();
        if (kind == ElementKind.CROSS_PARAMETER) {
            validationPath.nodes.pollLast();
        }
        if (kind == ElementKind.BEAN) {
            Path.Node node = validationPath.nodes.pollLast();
            ValidationPath.DefaultNode defaultNode = (ValidationPath.DefaultNode) node;
            next = new ValidationPath.MutableContainerContext(defaultNode.containerContext);
        }
    }

    private ValidationPath.MutableContainerContext newMutableContainerContext() {
        if (next != null) {
            ValidationPath.MutableContainerContext c = next;
            next = null;
            return c;
        }
        return new ValidationPath.MutableContainerContext();
    }

    @Override
    public NodeBuilderDefinedContext addNode(String name) {
        addPropertyNode(name);
        return new DefaultNodeBuilderDefinedContext();
    }

    @Override
    public NodeBuilderCustomizableContext addPropertyNode(String name) {
        ValidationPath.MutableContainerContext containerContext = newMutableContainerContext();
        validationPath.addPropertyNode(name, containerContext);
        return new DefaultNodeBuilderCustomizableContext(containerContext);
    }

    @Override
    public LeafNodeBuilderCustomizableContext addBeanNode() {
        ValidationPath.MutableContainerContext containerContext = newMutableContainerContext();
        validationPath.addBeanNode(containerContext);
        return new DefaultLeafNodeBuilderCustomizableContext(containerContext);
    }

    @Override
    public ContainerElementNodeBuilderCustomizableContext addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
        ValidationPath.MutableContainerContext containerContext = newMutableContainerContext();
        containerContext.inContainer(containerType, typeArgumentIndex);
        validationPath.addContainerElementNode(name, containerContext);
        return new DefaultContainerElementNodeBuilderCustomizableContext(containerContext);
    }

    @Override
    public NodeBuilderDefinedContext addParameterNode(int index) {
        Path.Node node = validationPath.nodes.peekLast();
        if (node == null || node.getKind() != ElementKind.METHOD) {
            throw new IllegalStateException("Cannot add parameter at path kind: " + (node == null ? "null" : node.getKind()));
        }
        String name = null;
        if (index != -1) {
            if (node instanceof ValidationPath.DefaultMethodNode methodNode) {
                name = methodNode.getMethodReference().getArguments()[index].getName();
            }
        }
        validationPath.addParameterNode(name, index);
        return new DefaultNodeBuilderDefinedContext();
    }

    @Override
    public ConstraintValidatorContext addConstraintViolation() {
        constraintValidatorContext.addViolation(new DefaultConstraintViolation<>(
            constraintValidatorContext.getRootBean(),
            constraintValidatorContext.getRootClass(),
            null,
            null,
            messageInterpolator.interpolate(messageTemplate, new DefaultMessageInterpolatorContext(
                constraintValidatorContext,
                constraintValidatorContext.constraint,
                null
            )),
            messageTemplate,
            validationPath.iterator().hasNext() ? validationPath : new ValidationPath(constraintValidatorContext.getCurrentPath()),
            constraintValidatorContext.constraint,
            null,
            null)
        );
        return constraintValidatorContext;
    }

    private final class DefaultContainerElementNodeBuilderDefinedContext implements ContainerElementNodeBuilderDefinedContext {

        @Override
        public NodeBuilderCustomizableContext addPropertyNode(String name) {
            return DefaultConstraintViolationBuilder.this.addPropertyNode(name);
        }

        @Override
        public LeafNodeBuilderCustomizableContext addBeanNode() {
            return DefaultConstraintViolationBuilder.this.addBeanNode();
        }

        @Override
        public ContainerElementNodeBuilderCustomizableContext addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
            return DefaultConstraintViolationBuilder.this.addContainerElementNode(name, containerType, typeArgumentIndex);
        }

        @Override
        public ConstraintValidatorContext addConstraintViolation() {
            return DefaultConstraintViolationBuilder.this.addConstraintViolation();
        }
    }

    private final class DefaultContainerElementNodeContextBuilder implements ContainerElementNodeContextBuilder {

        private final ValidationPath.MutableContainerContext containerContext;

        private DefaultContainerElementNodeContextBuilder(ValidationPath.MutableContainerContext containerContext) {
            this.containerContext = containerContext;
        }

        @Override
        public ContainerElementNodeBuilderDefinedContext atKey(Object key) {
            containerContext.atKey(key);
            return nodeBuilderDefinedContext;
        }

        @Override
        public ContainerElementNodeBuilderDefinedContext atIndex(Integer index) {
            containerContext.atIndex(index);
            return nodeBuilderDefinedContext;
        }

        @Override
        public NodeBuilderCustomizableContext addPropertyNode(String name) {
            return DefaultConstraintViolationBuilder.this.addPropertyNode(name);
        }

        @Override
        public LeafNodeBuilderCustomizableContext addBeanNode() {
            return DefaultConstraintViolationBuilder.this.addBeanNode();
        }

        @Override
        public ContainerElementNodeBuilderCustomizableContext addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
            return DefaultConstraintViolationBuilder.this.addContainerElementNode(name, containerType, typeArgumentIndex);
        }

        @Override
        public ConstraintValidatorContext addConstraintViolation() {
            return DefaultConstraintViolationBuilder.this.addConstraintViolation();
        }
    }

    private final class DefaultNodeBuilderDefinedContext implements NodeBuilderDefinedContext {

        @Override
        public NodeBuilderCustomizableContext addNode(String name) {
            return DefaultConstraintViolationBuilder.this.addPropertyNode(name);
        }

        @Override
        public NodeBuilderCustomizableContext addPropertyNode(String name) {
            return DefaultConstraintViolationBuilder.this.addPropertyNode(name);
        }

        @Override
        public LeafNodeBuilderCustomizableContext addBeanNode() {
            return DefaultConstraintViolationBuilder.this.addBeanNode();
        }

        @Override
        public ContainerElementNodeBuilderCustomizableContext addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
            return DefaultConstraintViolationBuilder.this.addContainerElementNode(name, containerType, typeArgumentIndex);
        }

        @Override
        public ConstraintValidatorContext addConstraintViolation() {
            return DefaultConstraintViolationBuilder.this.addConstraintViolation();
        }

    }

    private final class DefaultNodeContextBuilder implements NodeContextBuilder {

        private final ValidationPath.MutableContainerContext containerContext;

        private DefaultNodeContextBuilder(ValidationPath.MutableContainerContext containerContext) {
            this.containerContext = containerContext;
        }

        @Override
        public NodeBuilderDefinedContext atKey(Object key) {
            containerContext.atKey(key);
            return new DefaultNodeBuilderDefinedContext();
        }

        @Override
        public NodeBuilderDefinedContext atIndex(Integer index) {
            containerContext.atIndex(index);
            return new DefaultNodeBuilderDefinedContext();
        }

        @Override
        public NodeBuilderCustomizableContext addNode(String name) {
            return DefaultConstraintViolationBuilder.this.addPropertyNode(name);
        }

        @Override
        public NodeBuilderCustomizableContext addPropertyNode(String name) {
            return DefaultConstraintViolationBuilder.this.addPropertyNode(name);
        }

        @Override
        public LeafNodeBuilderCustomizableContext addBeanNode() {
            return DefaultConstraintViolationBuilder.this.addBeanNode();
        }

        @Override
        public ContainerElementNodeBuilderCustomizableContext addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
            return DefaultConstraintViolationBuilder.this.addContainerElementNode(name, containerType, typeArgumentIndex);
        }

        @Override
        public ConstraintValidatorContext addConstraintViolation() {
            return DefaultConstraintViolationBuilder.this.addConstraintViolation();
        }
    }

    private final class DefaultContainerElementNodeBuilderCustomizableContext implements ContainerElementNodeBuilderCustomizableContext {

        private final ValidationPath.MutableContainerContext containerContext;

        private DefaultContainerElementNodeBuilderCustomizableContext(ValidationPath.MutableContainerContext containerContext) {
            this.containerContext = containerContext;
        }

        @Override
        public ContainerElementNodeContextBuilder inIterable() {
            containerContext.inIterable();
            return new DefaultContainerElementNodeContextBuilder(containerContext);
        }

        @Override
        public NodeBuilderCustomizableContext addPropertyNode(String name) {
            return DefaultConstraintViolationBuilder.this.addPropertyNode(name);
        }

        @Override
        public LeafNodeBuilderCustomizableContext addBeanNode() {
            return DefaultConstraintViolationBuilder.this.addBeanNode();
        }

        @Override
        public ContainerElementNodeBuilderCustomizableContext addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
            return DefaultConstraintViolationBuilder.this.addContainerElementNode(name, containerType, typeArgumentIndex);
        }

        @Override
        public ConstraintValidatorContext addConstraintViolation() {
            return DefaultConstraintViolationBuilder.this.addConstraintViolation();
        }
    }

    private final class DefaultLeafNodeContextBuilder implements LeafNodeContextBuilder {

        private final ValidationPath.MutableContainerContext containerContext;

        private DefaultLeafNodeContextBuilder(ValidationPath.MutableContainerContext containerContext) {
            this.containerContext = containerContext;
        }

        @Override
        public LeafNodeBuilderDefinedContext atKey(Object key) {
            containerContext.atKey(key);
            return DefaultConstraintViolationBuilder.this::addConstraintViolation;
        }

        @Override
        public LeafNodeBuilderDefinedContext atIndex(Integer index) {
            containerContext.atIndex(index);
            return DefaultConstraintViolationBuilder.this::addConstraintViolation;
        }

        @Override
        public ConstraintValidatorContext addConstraintViolation() {
            return DefaultConstraintViolationBuilder.this.addConstraintViolation();
        }
    }

    private final class DefaultLeafNodeBuilderCustomizableContext implements LeafNodeBuilderCustomizableContext {

        private final ValidationPath.MutableContainerContext containerContext;

        private DefaultLeafNodeBuilderCustomizableContext(ValidationPath.MutableContainerContext containerContext) {
            this.containerContext = containerContext;
        }

        @Override
        public LeafNodeContextBuilder inIterable() {
            containerContext.inIterable();
            return new DefaultLeafNodeContextBuilder(containerContext);
        }

        @Override
        public LeafNodeBuilderCustomizableContext inContainer(Class<?> containerClass, Integer typeArgumentIndex) {
            containerContext.inContainer(containerClass, typeArgumentIndex);
            return new DefaultLeafNodeBuilderCustomizableContext(containerContext);
        }

        @Override
        public ConstraintValidatorContext addConstraintViolation() {
            return DefaultConstraintViolationBuilder.this.addConstraintViolation();
        }
    }

    private final class DefaultNodeBuilderCustomizableContext implements NodeBuilderCustomizableContext {

        private final ValidationPath.MutableContainerContext containerContext;

        private DefaultNodeBuilderCustomizableContext(ValidationPath.MutableContainerContext containerContext) {
            this.containerContext = containerContext;
        }

        @Override
        public NodeContextBuilder inIterable() {
            containerContext.inIterable();
            return new DefaultNodeContextBuilder(containerContext);
        }

        @Override
        public NodeBuilderCustomizableContext inContainer(Class<?> containerClass, Integer typeArgumentIndex) {
            containerContext.inContainer(containerClass, typeArgumentIndex);
            return this;
        }

        @Override
        public NodeBuilderCustomizableContext addNode(String name) {
            return DefaultConstraintViolationBuilder.this.addPropertyNode(name);
        }

        @Override
        public NodeBuilderCustomizableContext addPropertyNode(String name) {
            return DefaultConstraintViolationBuilder.this.addPropertyNode(name);
        }

        @Override
        public LeafNodeBuilderCustomizableContext addBeanNode() {
            return DefaultConstraintViolationBuilder.this.addBeanNode();
        }

        @Override
        public ContainerElementNodeBuilderCustomizableContext addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
            return DefaultConstraintViolationBuilder.this.addContainerElementNode(name, containerType, typeArgumentIndex);
        }

        @Override
        public ConstraintValidatorContext addConstraintViolation() {
            return DefaultConstraintViolationBuilder.this.addConstraintViolation();
        }
    }
}
