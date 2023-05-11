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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.inject.MethodReference;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Path implementation.
 *
 * @author Denis Stepanov
 * @since 4.0.0
 */
@Internal
final class ValidationPath implements Path {

    final Deque<Node> nodes;
    private ContainerContext containerContext = DefaultContainerContext.NONE;

    private final ContextualPath popPath = new ContextualPath() {
        @Override
        public void close() {
            nodes.removeLast();
        }
    };

    /**
     * Copy constructor.
     *
     * @param nodes The nodes
     */
    ValidationPath(ValidationPath nodes) {
        this.nodes = new LinkedList<>(nodes.nodes);
    }

    ValidationPath() {
        this.nodes = new LinkedList<>();
    }

    @Override
    public Iterator<Node> iterator() {
        return nodes.iterator();
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        final Iterator<Node> i = nodes.iterator();
        boolean dontAddDot = true;
        while (i.hasNext()) {
            final Node node = i.next();
            if (node.getKind() == ElementKind.BEAN) {
                continue;
            }
            if (node.isInIterable()) {
                builder.append('[');
                if (node.getIndex() != null) {
                    builder.append(node.getIndex());
                } else if (node.getKey() != null) {
                    builder.append(node.getKey());
                }
                builder.append(']');
            }
            if (node.getKind() == ElementKind.CONTAINER_ELEMENT) {
                if (!i.hasNext()) {
                    builder.append(node.getName());
                }
            } else {
                builder.append(dontAddDot ? "" : ".");
                builder.append(node.getName());
            }

            dontAddDot = false;
        }
        return builder.toString();
    }

    ContextualPath withContainerContext(ContainerContext containerContext) {
        ContainerContext prevContainerContext = this.containerContext;
        this.containerContext = containerContext;
        return () -> ValidationPath.this.containerContext = prevContainerContext;
    }

    ContextualPath addBeanNode() {
        return addBeanNode(containerContext);
    }

    ContextualPath addBeanNode(ContainerContext containerContext) {
        return addNode(new ValidationPath.DefaultBeanNode(containerContext));
    }

    ContextualPath addPropertyNode(String name) {
        return addPropertyNode(name, containerContext);
    }

    ContextualPath addPropertyNode(String name, ContainerContext containerContext) {
        return addNode(new ValidationPath.DefaultPropertyNode(name, containerContext));
    }

    ContextualPath addParameterNode(String name, int index) {
        return addNode(new ValidationPath.DefaultParameterNode(name, index));
    }

    ContextualPath addCrossParameterNode() {
        return addNode(new ValidationPath.DefaultCrossParameterNode());
    }

    ContextualPath addReturnValueNode() {
        return addNode(new ValidationPath.DefaultReturnValueNode());
    }

    ContextualPath addContainerElementNode(String name, ContainerContext containerContext) {
        return addNode(new ValidationPath.DefaultContainerElementNode(name, containerContext));
    }

    ContextualPath addMethodNode(MethodReference<?, ?> reference) {
        return addNode(new ValidationPath.DefaultMethodNode(reference));
    }

    private ContextualPath addNode(Node node) {
        nodes.add(node);
        ContextualPath contextualPath = withContainerContext(DefaultContainerContext.NONE);

        return () -> {
            popPath.close();
            contextualPath.close();
        };
    }

    ContextualPath addConstructorNode(String simpleName, Argument<?>... constructorArguments) {
        final ValidationPath.DefaultConstructorNode node = new ValidationPath.DefaultConstructorNode(new MethodReference<>() {

            @Override
            public Argument[] getArguments() {
                return constructorArguments;
            }

            @Override
            public Method getTargetMethod() {
                return null;
            }

            @Override
            public ReturnType<Object> getReturnType() {
                return null;
            }

            @Override
            public Class getDeclaringType() {
                return null;
            }

            @Override
            public String getMethodName() {
                return simpleName;
            }
        });
        nodes.add(node);
        return popPath;
    }

    public ContextualPath cascaded() {
        Node last = nodes.peekLast();
        if (containerContext.containerClass() == null && last != null && last.getKind() == ElementKind.CONTAINER_ELEMENT) {
            DefaultContainerElementNode removed = (DefaultContainerElementNode) nodes.removeLast();
            ContainerContext prevContainerContext = containerContext;
            containerContext = removed.containerContext;
            return () -> {
                nodes.add(removed);
                containerContext = prevContainerContext;
            };
        }
        return () -> {
        };
    }

    public Node last() {
        return nodes.peekLast();
    }

    public ConstraintTarget getConstraintTarget() {
        DefaultNode node = (DefaultNode) nodes.peekLast();
        return node == null ? ConstraintTarget.IMPLICIT : node.getConstraintTarget();
    }

    /**
     * Default Return value node implementation.
     */
    static final class DefaultReturnValueNode extends DefaultNode implements ReturnValueNode {

        public DefaultReturnValueNode() {
            super("<return value>", DefaultContainerContext.NONE);
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.RETURN_VALUE;
        }

        @Override
        public ConstraintTarget getConstraintTarget() {
            return ConstraintTarget.RETURN_VALUE;
        }
    }

    /**
     * Default bean node implementation.
     */
    static final class DefaultBeanNode extends DefaultNode implements BeanNode {
        public DefaultBeanNode(ContainerContext containerContext) {
            super(null, containerContext);
        }

        @Override
        public Class<?> getContainerClass() {
            return containerContext.containerClass();
        }

        @Override
        public Integer getTypeArgumentIndex() {
            return containerContext.typeArgumentIndex();
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.BEAN;
        }
    }

    /**
     * Default property node implementation.
     */
    static final class DefaultPropertyNode extends DefaultNode implements PropertyNode {
        public DefaultPropertyNode(String name, ContainerContext containerContext) {
            super(name, containerContext);
        }

        @Override
        public Class<?> getContainerClass() {
            return containerContext.containerClass();
        }

        @Override
        public Integer getTypeArgumentIndex() {
            return containerContext.typeArgumentIndex();
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.PROPERTY;
        }
    }

    /**
     * Default parameter node implementation.
     */
    static final class DefaultParameterNode extends DefaultNode implements ParameterNode {
        private final int parameterIndex;

        public DefaultParameterNode(@Nullable String name, int parameterIndex) {
            super(name, DefaultContainerContext.NONE);
            this.parameterIndex = parameterIndex;
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.PARAMETER;
        }

        @Override
        public int getParameterIndex() {
            return parameterIndex;
        }
    }

    /**
     * Default cross parameter node implementation.
     */
    private static final class DefaultCrossParameterNode extends DefaultNode implements CrossParameterNode {

        public DefaultCrossParameterNode() {
            super("<cross-parameter>", DefaultContainerContext.NONE);
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.CROSS_PARAMETER;
        }

        @Override
        public ConstraintTarget getConstraintTarget() {
            return ConstraintTarget.PARAMETERS;
        }
    }

    /**
     * Default node implementation.
     */
    abstract static class DefaultNode implements Node {
        protected final String name;
        protected final ContainerContext containerContext;

        public DefaultNode(String name, ContainerContext containerContext) {
            this.name = name;
            this.containerContext = containerContext;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isInIterable() {
            return containerContext.isInIterable();
        }

        @Override
        public Integer getIndex() {
            return containerContext.index();
        }

        @Override
        public Object getKey() {
            return containerContext.key();
        }

        @Override
        public String toString() {
            return name;
        }

        @Override
        public <T extends Node> T as(Class<T> nodeType) {
            if (nodeType.isInstance(this)) {
                return nodeType.cast(this);
            }
            throw new ClassCastException("Unexpected type: " + nodeType);
        }

        public ConstraintTarget getConstraintTarget() {
            return ConstraintTarget.IMPLICIT;
        }

    }

    /**
     * Method node implementation.
     */
    static class DefaultMethodNode extends DefaultNode implements MethodNode {

        private final MethodReference<?, ?> methodReference;

        public DefaultMethodNode(MethodReference<?, ?> methodReference) {
            super(methodReference.getMethodName(), DefaultContainerContext.NONE);
            this.methodReference = methodReference;
        }

        public MethodReference<?, ?> getMethodReference() {
            return methodReference;
        }

        @Override
        public List<Class<?>> getParameterTypes() {
            return Arrays.asList(methodReference.getArgumentTypes());
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.METHOD;
        }

    }

    /**
     * Default container element node implementation.
     */
    private static final class DefaultContainerElementNode extends DefaultNode implements ContainerElementNode {

        public DefaultContainerElementNode(@Nullable String name, @NonNull ContainerContext containerContext) {
            super(name, containerContext);
        }

        @Override
        public Class<?> getContainerClass() {
            return containerContext.containerClass();
        }

        @Override
        public Integer getTypeArgumentIndex() {
            return containerContext.typeArgumentIndex();
        }

        @Override
        public boolean isInIterable() {
            return containerContext.isInIterable();
        }

        @Override
        public Integer getIndex() {
            return containerContext.index();
        }

        @Override
        public Object getKey() {
            return containerContext.key();
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.CONTAINER_ELEMENT;
        }

    }

    /**
     * Default constructor node.
     */
    private static final class DefaultConstructorNode extends DefaultMethodNode implements ConstructorNode {
        public DefaultConstructorNode(MethodReference<Object, Object> methodReference) {
            super(methodReference);
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.CONSTRUCTOR;
        }
    }

    public interface ContainerContext {
        static ContainerContext indexed(Class<?> containerClass, int index, Integer typeArgumentIndex) {
            return new ValidationPath.DefaultContainerContext(containerClass, index, null, true, typeArgumentIndex);
        }

        static ContainerContext keyed(Class<?> containerClass, Object key, Integer typeArgumentIndex) {
            return new ValidationPath.DefaultContainerContext(containerClass, null, key, true, typeArgumentIndex);
        }

        static ContainerContext iterable(Class<?> containerClass, Integer typeArgumentIndex) {
            return new ValidationPath.DefaultContainerContext(containerClass, null, null, true, typeArgumentIndex);
        }

        static ContainerContext value(Class<?> containerClass, Integer typeArgumentIndex) {
            return new ValidationPath.DefaultContainerContext(containerClass, null, null, false, typeArgumentIndex);
        }

        Class<?> containerClass();

        Integer index();

        Object key();

        boolean isInIterable();

        Integer typeArgumentIndex();
    }

    /**
     * The container context.
     *
     * @param containerClass    The container class
     * @param index             The index
     * @param key               The key
     * @param isInIterable      Is iterable
     * @param typeArgumentIndex The type argument index
     */
    @Internal
    public record DefaultContainerContext(@Nullable Class<?> containerClass,
                                          @Nullable Integer index,
                                          @Nullable Object key,
                                          boolean isInIterable,
                                          @Nullable Integer typeArgumentIndex) implements ContainerContext {

        /**
         * Not in a container context.
         */
        static DefaultContainerContext NONE = new DefaultContainerContext(null, null, null, false, null);

        /**
         * The iterable container context.
         *
         * @param containerClass The container class
         * @return new context
         */
        public static DefaultContainerContext ofIterableContainer(Class<?> containerClass) {
            return new DefaultContainerContext(containerClass, null, null, true, 0);
        }

        public static DefaultContainerContext ofContainer(Class<?> containerClass) {
            return new DefaultContainerContext(containerClass, null, null, false, 0);
        }

    }

    /**
     * The mutable container context.
     *
     */
    @Internal
    public static class MutableContainerContext implements ContainerContext {

        private @Nullable Class<?> containerClass;
        private @Nullable Integer index;
        private  @Nullable Object key;
        private  boolean isInIterable;
        private  @Nullable Integer typeArgumentIndex;

        public MutableContainerContext() {
        }

        public MutableContainerContext(ValidationPath.ContainerContext containerContext) {
            containerClass = containerContext.containerClass();
            index = containerContext.index();
            key = containerContext.key();
            isInIterable = containerContext.isInIterable();
            typeArgumentIndex = containerContext.typeArgumentIndex();
        }

        @Override
        public Class<?> containerClass() {
            return containerClass;
        }

        @Override
        public Integer index() {
            return index;
        }

        @Override
        public Object key() {
            return key;
        }

        @Override
        public boolean isInIterable() {
            return isInIterable;
        }

        @Override
        public Integer typeArgumentIndex() {
            return typeArgumentIndex;
        }

        public void inIterable() {
            isInIterable = true;
        }

        public void inContainer(Class<?> containerClass, Integer typeArgumentIndex) {
            this.containerClass = containerClass;
            this.typeArgumentIndex = typeArgumentIndex;
        }

        public void atKey(Object key) {
            this.key = key;
        }

        public void atIndex(Integer index) {
            this.index = index;
        }
    }

    @Internal
    public interface ContextualPath extends AutoCloseable {
        @Override
        void close();
    }
}
