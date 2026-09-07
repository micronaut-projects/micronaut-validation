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

import io.micronaut.context.ExecutionHandleLocator;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanConstructor;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.reflection.MethodHierarchy;
import io.micronaut.reflection.ReflectionAnnotations;
import io.micronaut.reflection.ReflectionArguments;
import io.micronaut.reflection.ReflectionExecutables;
import io.micronaut.reflection.ReflectiveIntrospection;
import io.micronaut.validation.validator.ExecutableHierarchy;
import io.micronaut.validation.validator.ReflectionSupport;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedType;
import jakarta.validation.ValidationException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * The {@link ReflectionSupport} over the reflection module of micronaut-core: what the generated metadata does
 * not describe is read from the class itself.
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
public final class ReflectionValidationSupport implements ReflectionSupport {

    /**
     * Creates the support; it is instantiated by the service loader.
     */
    public ReflectionValidationSupport() {
        // the service loader needs a no-arg constructor and there is no state to set up
    }

    @Override
    public <T> ExecutableMethod<T, Object> executableMethod(ExecutionHandleLocator locator, BeanIntrospector introspector, Method method) {
        return ReflectionExecutables.executableMethod(locator, introspector, method);
    }

    @Override
    public <T> BeanConstructor<T> beanConstructor(@Nullable BeanIntrospection<T> introspection, Constructor<T> constructor) {
        return ReflectionExecutables.beanConstructor(introspection, constructor);
    }

    @Override
    public ExecutableHierarchy.Resolved resolveHierarchy(BeanIntrospector introspector, ExecutableHierarchy.Declaration local, String name) {
        MethodHierarchy hierarchy = MethodHierarchy.resolve(introspector, toCore(local), name);
        // the levels are merged here rather than taken merged from core: a declaration of the validator carries
        // the method annotations on its return argument, which a reflective declaration of core does not
        return ExecutableHierarchy.merge(local,
            fromCore(hierarchy.declared()),
            hierarchy.inherited().stream().map(ReflectionValidationSupport::fromCore).toList());
    }

    private static MethodHierarchy.Declaration toCore(ExecutableHierarchy.Declaration declaration) {
        return new MethodHierarchy.Declaration(declaration.declaringType(),
            declaration.annotationMetadata(),
            declaration.arguments(),
            declaration.returnArgument(),
            declaration.exact());
    }

    private static ExecutableHierarchy.Declaration fromCore(MethodHierarchy.Declaration declaration) {
        return new ExecutableHierarchy.Declaration(declaration.declaringType(),
            declaration.annotationMetadata(),
            declaration.arguments(),
            declaration.returnArgument(),
            declaration.exact());
    }

    @Override
    public boolean separatesDeclarations(BeanIntrospection<?> introspection) {
        return introspection instanceof ReflectiveIntrospection<?>;
    }

    @Override
    public List<ComposingConstraint> composingConstraints(Class<? extends Annotation> constraintType, AnnotationValue<? extends Annotation> parentAnnotationValue) {
        return ReflectedComposition.composingConstraints(constraintType, parentAnnotationValue);
    }

    @Override
    public void checkComposition(Class<? extends Annotation> constraintType, AnnotationValue<? extends Annotation> parentAnnotationValue) {
        ReflectedComposition.checkDeclaredComposition(constraintType, parentAnnotationValue);
    }

    @Override
    public Argument<?> boundTypeArgument(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        return ReflectionContainerTypeArguments.boundTypeArgument(declaredType, containerType, typeArgumentIndex);
    }

    @Override
    public Integer extractedTypeArgumentIndex(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        return ReflectionContainerTypeArguments.extractedTypeArgumentIndex(declaredType, containerType, typeArgumentIndex);
    }

    @Override
    public <T> T instantiate(Class<T> type) {
        Constructor<T> constructor;
        try {
            constructor = type.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            return null;
        }
        try {
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InvocationTargetException e) {
            throw new ValidationException("Cannot instantiate the constraint validator: " + type.getName(), e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot instantiate the constraint validator: " + type.getName(), e);
        }
    }

    @Override
    public void checkConstraintDefinition(Class<? extends Annotation> constraintType) {
        ReflectedConstraintDefinitions.validate(constraintType);
    }

    @Override
    public <T> Argument<T> genericSuperArgument(Class<?> type, Class<T> superType) {
        return ReflectionGenericArguments.resolveGenericToArgument(type, superType);
    }

    @Override
    public Argument<?> argumentOf(AnnotatedType type) {
        return ReflectionArguments.of(type);
    }

    @Override
    public AnnotationMetadata annotationMetadataOf(AnnotatedElement element) {
        return ReflectionAnnotations.metadataOf(element);
    }
}
