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

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.Valid;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reflection-only Jakarta executable inheritance declaration checks.
 *
 * @since 5.1
 */
final class ReflectionMethodDeclarations {

    private ReflectionMethodDeclarations() {
    }

    static void validateParameterDeclarations(Method method) {
        List<Method> inheritedMethods = inheritedMethods(method);
        if (hasParameterConstraintsOrCascades(method) && !inheritedMethods.isEmpty()) {
            throw new ConstraintDeclarationException("Parameter constraints cannot be added in overriding or implementing methods");
        }
        if (inheritedMethods.size() > 1 && inheritedMethods.stream().anyMatch(ReflectionMethodDeclarations::hasParameterConstraintsOrCascades)) {
            throw new ConstraintDeclarationException("Parallel method declarations cannot declare parameter constraints");
        }
    }

    static void validateReturnValueDeclarations(Method method) {
        List<Method> inheritedMethods = inheritedMethods(method);
        long inheritedCascadedReturns = inheritedMethods.stream()
            .filter(ReflectionMethodDeclarations::hasCascadedReturnValue)
            .count();
        if (hasCascadedReturnValue(method) && inheritedCascadedReturns > 0) {
            throw new ConstraintDeclarationException("Return value cannot be marked cascaded more than once in a method hierarchy");
        }
        if (inheritedCascadedReturns > 1) {
            throw new ConstraintDeclarationException("Return value cannot be marked cascaded more than once in a method hierarchy");
        }
    }

    private static boolean hasParameterConstraintsOrCascades(Method method) {
        for (Parameter parameter : method.getParameters()) {
            if (hasConstraints(parameter)
                || parameter.isAnnotationPresent(Valid.class)
                || hasConstraintsOrCascades(parameter.getAnnotatedType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCascadedReturnValue(Method method) {
        return method.isAnnotationPresent(Valid.class) || hasCascades(method.getAnnotatedReturnType());
    }

    private static boolean hasConstraintsOrCascades(AnnotatedType annotatedType) {
        if (hasConstraints(annotatedType) || annotatedType.isAnnotationPresent(Valid.class)) {
            return true;
        }
        if (annotatedType instanceof AnnotatedParameterizedType parameterizedType) {
            for (AnnotatedType typeArgument : parameterizedType.getAnnotatedActualTypeArguments()) {
                if (hasConstraintsOrCascades(typeArgument)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasCascades(AnnotatedType annotatedType) {
        if (annotatedType.isAnnotationPresent(Valid.class)) {
            return true;
        }
        if (annotatedType instanceof AnnotatedParameterizedType parameterizedType) {
            for (AnnotatedType typeArgument : parameterizedType.getAnnotatedActualTypeArguments()) {
                if (hasCascades(typeArgument)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasConstraints(AnnotatedElement element) {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(Constraint.class) || isConstraintContainer(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConstraintContainer(Annotation container) {
        try {
            Method valueMethod = container.annotationType().getDeclaredMethod("value");
            if (!valueMethod.getReturnType().isArray() || !Annotation.class.isAssignableFrom(valueMethod.getReturnType().getComponentType())) {
                return false;
            }
            valueMethod.setAccessible(true);
            Annotation[] annotations = (Annotation[]) valueMethod.invoke(container);
            for (Annotation annotation : annotations) {
                if (annotation.annotationType().isAnnotationPresent(Constraint.class)) {
                    return true;
                }
            }
            return false;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new ConstraintDeclarationException("Cannot read constraint container " + container.annotationType().getName(), e);
        }
    }

    private static List<Method> inheritedMethods(Method method) {
        List<Method> methods = new ArrayList<>();
        Set<Class<?>> visitedInterfaces = new LinkedHashSet<>();
        Class<?> declaringClass = method.getDeclaringClass();
        for (Class<?> current = declaringClass.getSuperclass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Method inheritedMethod = findDeclaredMethod(current, method);
            if (inheritedMethod != null) {
                methods.add(inheritedMethod);
            }
            collectInterfaceMethods(current, method, visitedInterfaces, methods);
        }
        collectInterfaceMethods(declaringClass, method, visitedInterfaces, methods);
        return methods;
    }

    private static void collectInterfaceMethods(Class<?> type,
                                                Method method,
                                                Set<Class<?>> visitedInterfaces,
                                                List<Method> methods) {
        for (Class<?> interfaceType : type.getInterfaces()) {
            collectInterfaceMethod(interfaceType, method, visitedInterfaces, methods);
        }
    }

    private static void collectInterfaceMethod(Class<?> interfaceType,
                                               Method method,
                                               Set<Class<?>> visitedInterfaces,
                                               List<Method> methods) {
        if (!visitedInterfaces.add(interfaceType)) {
            return;
        }
        Method interfaceMethod = findDeclaredMethod(interfaceType, method);
        if (interfaceMethod != null) {
            methods.add(interfaceMethod);
        }
        for (Class<?> parent : interfaceType.getInterfaces()) {
            collectInterfaceMethod(parent, method, visitedInterfaces, methods);
        }
    }

    private static Method findDeclaredMethod(Class<?> type, Method method) {
        try {
            return type.getDeclaredMethod(method.getName(), method.getParameterTypes());
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
