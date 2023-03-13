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

import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ConstructorDescriptor;
import jakarta.validation.metadata.ElementDescriptor;
import jakarta.validation.metadata.MethodDescriptor;
import jakarta.validation.metadata.MethodType;
import jakarta.validation.metadata.PropertyDescriptor;
import jakarta.validation.metadata.Scope;

import java.lang.annotation.ElementType;
import java.util.Collections;
import java.util.Set;

/**
 * An empty descriptor with no constraints.
 *
 * @param elementClass the class of element
 */
record EmptyDescriptor(
    Class<?> elementClass
) implements BeanDescriptor, ElementDescriptor.ConstraintFinder {

    @Override
    public boolean isBeanConstrained() {
        return false;
    }

    @Override
    public PropertyDescriptor getConstraintsForProperty(String propertyName) {
        return null;
    }

    @Override
    public Set<PropertyDescriptor> getConstrainedProperties() {
        return Collections.emptySet();
    }

    @Override
    public MethodDescriptor getConstraintsForMethod(String methodName, Class<?>... parameterTypes) {
        return null;
    }

    @Override
    public Set<MethodDescriptor> getConstrainedMethods(MethodType methodType, MethodType... methodTypes) {
        return Collections.emptySet();
    }

    @Override
    public ConstructorDescriptor getConstraintsForConstructor(Class<?>... parameterTypes) {
        return null;
    }

    @Override
    public Set<ConstructorDescriptor> getConstrainedConstructors() {
        return Collections.emptySet();
    }

    @Override
    public boolean hasConstraints() {
        return false;
    }

    @Override
    public Class<?> getElementClass() {
        return elementClass;
    }

    @Override
    public ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
        return this;
    }

    @Override
    public ConstraintFinder lookingAt(Scope scope) {
        return this;
    }

    @Override
    public ConstraintFinder declaredOn(ElementType... types) {
        return this;
    }

    @Override
    public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
        return Collections.emptySet();
    }

    @Override
    public ConstraintFinder findConstraints() {
        return this;
    }
}
