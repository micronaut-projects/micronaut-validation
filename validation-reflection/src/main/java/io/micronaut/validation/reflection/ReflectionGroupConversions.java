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

import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.GroupSequence;
import jakarta.validation.Valid;
import jakarta.validation.groups.ConvertGroup;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reflection-only Jakarta group conversion declaration checks.
 *
 * @since 5.1
 */
final class ReflectionGroupConversions {

    private ReflectionGroupConversions() {
    }

    static void validateBean(Class<?> beanType) {
        for (Class<?> current = beanType; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                validateElement(field, field.isAnnotationPresent(Valid.class));
                validateAnnotatedType(field.getAnnotatedType());
            }
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() != 0
                    || method.getReturnType() == Void.TYPE
                    || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                validateElement(method, method.isAnnotationPresent(Valid.class));
                validateAnnotatedType(method.getAnnotatedReturnType());
            }
        }
    }

    static void validateMethodParameterDeclarations(Method method) {
        Parameter[] parameters = method.getParameters();
        boolean methodDeclaresParameterConversion = false;
        for (Parameter parameter : parameters) {
            validateElement(parameter, parameter.isAnnotationPresent(Valid.class));
            validateAnnotatedType(parameter.getAnnotatedType());
            methodDeclaresParameterConversion |= hasGroupConversions(parameter) || hasGroupConversions(parameter.getAnnotatedType());
        }
        List<Method> inheritedMethods = inheritedMethods(method);
        if (methodDeclaresParameterConversion && !inheritedMethods.isEmpty()) {
            throw new ConstraintDeclarationException(
                "Group conversions on parameters cannot be added in overriding or implementing methods"
            );
        }
        if (inheritedMethods.size() > 1 && inheritedMethods.stream().anyMatch(ReflectionGroupConversions::hasParameterGroupConversions)) {
            throw new ConstraintDeclarationException("Parallel method declarations cannot declare parameter group conversions");
        }
    }

    static void validateMethodReturnValueDeclarations(Method method) {
        validateElement(method, method.isAnnotationPresent(Valid.class));
        validateAnnotatedType(method.getAnnotatedReturnType());
        List<Method> inheritedMethods = inheritedMethods(method);
        if (inheritedMethods.size() > 1 && inheritedMethods.stream().anyMatch(ReflectionGroupConversions::hasReturnValueGroupConversions)) {
            throw new ConstraintDeclarationException("Parallel method declarations cannot declare return value group conversions");
        }
    }

    static void validateConstructorParameterDeclarations(Constructor<?> constructor) {
        for (Parameter parameter : constructor.getParameters()) {
            validateElement(parameter, parameter.isAnnotationPresent(Valid.class));
            validateAnnotatedType(parameter.getAnnotatedType());
        }
    }

    static void validateConstructorReturnValueDeclaration(Constructor<?> constructor) {
        validateElement(constructor, constructor.isAnnotationPresent(Valid.class));
        validateAnnotatedType(constructor.getAnnotatedReturnType());
    }

    private static void validateAnnotatedType(AnnotatedType annotatedType) {
        validateElement(annotatedType, annotatedType.isAnnotationPresent(Valid.class));
        if (annotatedType instanceof AnnotatedParameterizedType parameterizedType) {
            for (AnnotatedType typeArgument : parameterizedType.getAnnotatedActualTypeArguments()) {
                validateAnnotatedType(typeArgument);
            }
        }
    }

    private static void validateElement(AnnotatedElement element, boolean cascaded) {
        ConvertGroup[] groupConversions = element.getAnnotationsByType(ConvertGroup.class);
        if (groupConversions.length == 0) {
            return;
        }
        if (!cascaded) {
            throw new ConstraintDeclarationException("Group conversions can only be declared on cascaded elements");
        }
        Map<Class<?>, Class<?>> conversions = new LinkedHashMap<>();
        for (ConvertGroup groupConversion : groupConversions) {
            Class<?> from = groupConversion.from();
            if (from.isAnnotationPresent(GroupSequence.class)) {
                throw new ConstraintDeclarationException("Group conversion source cannot be a group sequence: " + from.getName());
            }
            Class<?> previous = conversions.putIfAbsent(from, groupConversion.to());
            if (previous != null) {
                throw new ConstraintDeclarationException("Multiple group conversions declare the same source group: " + from.getName());
            }
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

    private static boolean hasParameterGroupConversions(Method method) {
        for (Parameter parameter : method.getParameters()) {
            if (hasGroupConversions(parameter) || hasGroupConversions(parameter.getAnnotatedType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasReturnValueGroupConversions(Method method) {
        return hasGroupConversions(method) || hasGroupConversions(method.getAnnotatedReturnType());
    }

    private static boolean hasGroupConversions(AnnotatedElement element) {
        if (element.getAnnotationsByType(ConvertGroup.class).length > 0) {
            return true;
        }
        if (element instanceof AnnotatedParameterizedType parameterizedType) {
            for (AnnotatedType typeArgument : parameterizedType.getAnnotatedActualTypeArguments()) {
                if (hasGroupConversions(typeArgument)) {
                    return true;
                }
            }
        }
        return false;
    }
}
