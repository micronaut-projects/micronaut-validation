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

import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;
import java.util.List;

/**
 * Container element path context used by reflective validation.
 *
 * @param nodeName The extracted node name
 * @param iterable Whether the extracted value is iterable
 * @param key The extracted key
 * @param index The extracted index
 * @param containerClass The container class
 * @param typeArgumentIndex The container type argument index
 * @since 5.1
 */
record ReflectionContainerContext(@Nullable String nodeName,
                                  boolean iterable,
                                  @Nullable Object key,
                                  @Nullable Integer index,
                                  Class<?> containerClass,
                                  @Nullable Integer typeArgumentIndex) {
}

/**
 * Bean node reached through a cascaded container element.
 *
 * @param containerContext The container context
 * @since 5.1
 */
record ReflectionContainerBeanNode(ReflectionContainerContext containerContext) implements Path.BeanNode {

    @Override
    public ElementKind getKind() {
        return ElementKind.BEAN;
    }

    @Override
    public boolean isInIterable() {
        return containerContext.iterable();
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
    public String getName() {
        return null;
    }

    @Override
    public <T extends Path.Node> T as(Class<T> nodeType) {
        return nodeType.cast(this);
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
    public String toString() {
        return "";
    }
}

/**
 * Path to a reflected container element constraint.
 *
 * @param propertyName The owning property name
 * @param containerContext The container context
 * @since 5.1
 */
record ReflectionContainerElementPath(String propertyName,
                                      ReflectionContainerContext containerContext) implements Path {

    @Override
    public Iterator<Node> iterator() {
        if (containerContext.nodeName() == null) {
            return List.<Node>of(new ReflectionContainerPropertyNode(propertyName)).iterator();
        }
        return List.<Node>of(
            new ReflectionContainerPropertyNode(propertyName),
            new ReflectionContainerElementNode(containerContext)
        ).iterator();
    }

    @Override
    public String toString() {
        return propertyName + "." + containerContext.nodeName();
    }
}

/**
 * Path to a reflected cascaded property reached through a container element.
 *
 * @param propertyName The owning property name
 * @param nestedPropertyName The nested property name
 * @param containerContext The container context
 * @since 5.1
 */
record ReflectionContainerPropertyPath(String propertyName,
                                       String nestedPropertyName,
                                       ReflectionContainerContext containerContext) implements Path {

    @Override
    public Iterator<Node> iterator() {
        return List.<Node>of(
            new ReflectionContainerPropertyNode(propertyName),
            new ReflectionContainerPropertyNode(
                nestedPropertyName,
                containerContext.iterable(),
                containerContext.key(),
                containerContext.index(),
                containerContext.containerClass(),
                containerContext.typeArgumentIndex()
            )
        ).iterator();
    }

    @Override
    public String toString() {
        return propertyName + "." + nestedPropertyName;
    }
}

record ReflectionContainerPropertyNode(String name,
                                       boolean inIterable,
                                       @Nullable Object key,
                                       @Nullable Integer index,
                                       @Nullable Class<?> containerClass,
                                       @Nullable Integer typeArgumentIndex) implements Path.PropertyNode {

    ReflectionContainerPropertyNode(String name) {
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
        return name;
    }
}

record ReflectionContainerElementNode(ReflectionContainerContext containerContext) implements Path.ContainerElementNode {

    @Override
    public ElementKind getKind() {
        return ElementKind.CONTAINER_ELEMENT;
    }

    @Override
    public boolean isInIterable() {
        return containerContext.iterable();
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
    public String getName() {
        return containerContext.nodeName();
    }

    @Override
    public <T extends Path.Node> T as(Class<T> nodeType) {
        return nodeType.cast(this);
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
    public String toString() {
        return containerContext.nodeName();
    }
}
