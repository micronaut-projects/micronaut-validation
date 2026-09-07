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
package io.micronaut.validation.validator;

import io.micronaut.context.ExecutionHandleLocator;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanConstructor;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import jakarta.validation.ValidationException;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The {@link ReflectionSupport} reading the generated metadata and nothing else: an executable a caller names
 * is the one of a bean definition or of a bean introspection, the hierarchy of an executable is the one the
 * introspections of the super types describe, and what only reflection could read is not read.
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
final class CompileTimeSupport implements ReflectionSupport {

    @Override
    @SuppressWarnings("unchecked")
    public <T> ExecutableMethod<T, Object> executableMethod(ExecutionHandleLocator locator, BeanIntrospector introspector, Method method) {
        Class<T> declaringType = (Class<T>) method.getDeclaringClass();
        Optional<ExecutableMethod<T, Object>> found = locator.findExecutableMethod(declaringType, method.getName(), method.getParameterTypes());
        // the locator answers for any bean of the type, a sub type overriding the method included, and falls
        // back to a match on the name alone: only the method the type of the method declares is the one named
        if (found.isPresent()
            && found.get().getDeclaringType() == declaringType
            && Arrays.equals(found.get().getArgumentTypes(), method.getParameterTypes())) {
            return found.get();
        }
        BeanIntrospection<T> introspection = introspector.findIntrospection(declaringType).orElse(null);
        if (introspection != null) {
            for (BeanMethod<T, Object> beanMethod : introspection.getBeanMethods()) {
                if (beanMethod.getName().equals(method.getName())
                    && Arrays.equals(Argument.toClassArray(beanMethod.getArguments()), method.getParameterTypes())) {
                    return new IntrospectedExecutable<>(declaringType, beanMethod, method);
                }
            }
        }
        throw new ValidationException("No metadata describes the method " + method.getName() + Arrays.toString(method.getParameterTypes())
            + " of " + declaringType.getName() + ": the type is neither a bean nor introspected with the method"
            + " listed as executable, and the micronaut-validation-reflection module, which would describe it reflectively, is not present");
    }

    @Override
    public <T> BeanConstructor<T> beanConstructor(@Nullable BeanIntrospection<T> introspection, Constructor<T> constructor) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (introspection != null) {
            for (BeanConstructor<T> candidate : introspection.getConstructors()) {
                if (Arrays.equals(Argument.toClassArray(candidate.getArguments()), parameterTypes)) {
                    return candidate;
                }
            }
        }
        throw new ValidationException("No metadata describes the constructor " + constructor.getDeclaringClass().getName() + Arrays.toString(parameterTypes)
            + ": the type is not introspected with that constructor, and the micronaut-validation-reflection module,"
            + " which would describe it reflectively, is not present");
    }

    @Override
    public ExecutableHierarchy.Resolved resolveHierarchy(BeanIntrospector introspector, ExecutableHierarchy.Declaration local, String name) {
        return ExecutableHierarchy.resolve(introspector, local, name);
    }

    @Override
    public boolean separatesDeclarations(BeanIntrospection<?> introspection) {
        return false;
    }

    @Override
    public List<ComposingConstraint> composingConstraints(Class<? extends Annotation> constraintType, AnnotationValue<? extends Annotation> parentAnnotationValue) {
        return List.of();
    }

    @Override
    public void checkComposition(Class<? extends Annotation> constraintType, AnnotationValue<? extends Annotation> parentAnnotationValue) {
        // the declared form of the annotation type is not read: the rules the retained tree cannot answer are not checked
    }

    @Override
    public Argument<?> boundTypeArgument(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        throw missing("what " + declaredType.getName() + " binds the type argument " + typeArgumentIndex
            + " of " + containerType.getName() + " to");
    }

    @Override
    public Integer extractedTypeArgumentIndex(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        throw missing("which type argument of " + declaredType.getName() + " carries the one extracted from "
            + containerType.getName());
    }

    @Override
    public <T> Argument<T> genericSuperArgument(Class<?> type, Class<T> superType) {
        throw missing("what " + type.getName() + " binds the type arguments of " + superType.getName() + " to");
    }

    @Override
    public Argument<?> argumentOf(AnnotatedType type) {
        throw missing("the annotations of the type " + type.getType().getTypeName());
    }

    @Override
    public AnnotationMetadata annotationMetadataOf(AnnotatedElement element) {
        throw missing("the annotations of " + element);
    }

    /**
     * The generated metadata does not describe what was asked for, and reading it means reading the class,
     * which is what the reflection module is for.
     */
    private static ValidationException missing(String what) {
        return new ValidationException("No generated metadata describes " + what
            + ": reading it means reading the class, and the micronaut-validation-reflection module,"
            + " which would read it, is not present");
    }
}
