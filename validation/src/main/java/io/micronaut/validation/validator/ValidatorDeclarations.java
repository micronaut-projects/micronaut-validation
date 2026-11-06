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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanTypeHierarchy;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import jakarta.validation.ParameterNameProvider;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the validator knows about the declarations it validates: the hierarchies of the executables,
 * the super types of the beans, and the declaration and definition rules checked once per
 * declaration.
 *
 * @since 5.0.0
 */
@Internal
final class ValidatorDeclarations {

    private final ReflectionSupport reflectionSupport;
    // the provider of the application naming the parameters, null for the names compiled into the metadata
    private final @Nullable ParameterNameProvider parameterNameProvider;
    private final BeanIntrospector beanIntrospector;
    private final boolean strictConstraintDefinitions;
    private final Map<ExecutableHierarchy.Key, ExecutableHierarchy.Resolved> executableHierarchies = new ConcurrentHashMap<>();
    private final Set<Class<?>> checkedConstraintDefinitions = ConcurrentHashMap.newKeySet();
    private final Set<BeanIntrospection<?>> checkedBeanDeclarations = ConcurrentHashMap.newKeySet();
    // the methods whose hierarchy declares its parameters, or its return value, as a hierarchy may: checked once
    private final Set<ExecutableHierarchy.Key> checkedParameterDeclarations = ConcurrentHashMap.newKeySet();
    private final Set<ExecutableHierarchy.Key> checkedReturnValueDeclarations = ConcurrentHashMap.newKeySet();
    private final Map<BeanIntrospection<?>, List<BeanIntrospection<?>>> superIntrospectionsCache = new ConcurrentHashMap<>();
    private final Map<ExecutableHierarchy.Key, ConfiguredExecutable> configuredExecutables = new ConcurrentHashMap<>();

    ValidatorDeclarations(BeanIntrospector beanIntrospector, boolean strictConstraintDefinitions, ReflectionSupport reflectionSupport) {
        this(beanIntrospector, strictConstraintDefinitions, reflectionSupport, null);
    }

    ValidatorDeclarations(BeanIntrospector beanIntrospector, boolean strictConstraintDefinitions, ReflectionSupport reflectionSupport,
                          @Nullable ParameterNameProvider parameterNameProvider) {
        this.parameterNameProvider = parameterNameProvider instanceof DefaultParameterNameProvider ? null : parameterNameProvider;
        this.reflectionSupport = reflectionSupport;
        this.beanIntrospector = beanIntrospector;
        this.strictConstraintDefinitions = strictConstraintDefinitions;
    }

    ReflectionSupport reflectionSupport() {
        return reflectionSupport;
    }

    void clear() {
        executableHierarchies.clear();
        checkedConstraintDefinitions.clear();
        checkedBeanDeclarations.clear();
        checkedParameterDeclarations.clear();
        checkedReturnValueDeclarations.clear();
        superIntrospectionsCache.clear();
        configuredExecutables.clear();
    }

    /**
     * The names the parameter name provider of the application gives the parameters of a method, as the
     * descriptors of the method report them.
     *
     * @param declaringType The type declaring the method
     * @param name The name of the method
     * @param arguments The parameters
     * @return The names, or null for the names compiled into the metadata
     */
    @Nullable List<String> parameterNames(Class<?> declaringType, String name, Argument<?>[] arguments) {
        if (parameterNameProvider == null) {
            return null;
        }
        java.lang.reflect.Method method = reflectionSupport.declaredMethod(declaringType, name, Argument.toClassArray(arguments));
        return method == null ? null : parameterNameProvider.getParameterNames(method);
    }

    /** The hierarchy of a bean method, for the descriptors of a bean. */
    ExecutableHierarchy.Resolved resolveHierarchy(BeanMethod<?, ?> method) {
        return reflectionSupport.resolveHierarchy(beanIntrospector, ExecutableHierarchy.Declaration.of(method, method.getDeclaringBean().separatesDeclarations()), method.getName());
    }

    /** A method with what it inherits, computed once per method. */
    ConfiguredExecutable configuredExecutable(ExecutableMethod<?, ?> method, ExecutableHierarchy.Resolved hierarchy) {
        return configuredExecutables.computeIfAbsent(ExecutableHierarchy.Key.of(method), key -> new ConfiguredExecutable(
            hierarchy.annotationMetadata(),
            hierarchy.arguments(),
            hierarchy.returnArgument()
        ));
    }

    /**
     * A constructor as its introspection describes it, computed once per constructor. A caller describing the
     * constructor with other metadata, such as arguments alone, gets its own description: it neither reuses the
     * cached one nor replaces it.
     */
    ConfiguredExecutable configuredConstructor(Class<?> beanType, AnnotationMetadata annotationMetadata, Argument<?>[] arguments) {
        ExecutableHierarchy.Key key = new ExecutableHierarchy.Key(beanType, "<init>", List.of(Argument.toClassArray(arguments)));
        ConfiguredExecutable configured = configuredExecutables.get(key);
        if (configured != null && configured.annotationMetadata() == annotationMetadata && configured.arguments() == arguments) {
            return configured;
        }
        configured = new ConfiguredExecutable(annotationMetadata, arguments, Argument.of(beanType, annotationMetadata));
        if (annotationMetadata != AnnotationMetadata.EMPTY_METADATA) {
            configuredExecutables.put(key, configured);
        }
        return configured;
    }

    ExecutableHierarchy.Resolved resolveHierarchy(ExecutableMethod<?, ?> method) {
        return executableHierarchies.computeIfAbsent(ExecutableHierarchy.Key.of(method),
            key -> reflectionSupport.resolveHierarchy(beanIntrospector, ExecutableHierarchy.Declaration.of(method), method.getMethodName()));
    }

    /**
     * The parameters a hierarchy declares are checked the first time the method is validated, a failed check
     * failing every validation of it.
     */
    void checkParameterDeclarations(ExecutableMethod<?, ?> method, ExecutableHierarchy.Resolved hierarchy) {
        ExecutableHierarchy.Key key = ExecutableHierarchy.Key.of(method);
        if (!checkedParameterDeclarations.contains(key)) {
            ExecutableHierarchy.checkParameterDeclarations(reflectionSupport, hierarchy);
            checkedParameterDeclarations.add(key);
        }
    }

    /**
     * The return value a hierarchy declares is checked the first time the method is validated, a failed check
     * failing every validation of it.
     */
    void checkReturnValueDeclarations(ExecutableMethod<?, ?> method, ExecutableHierarchy.Resolved hierarchy) {
        ExecutableHierarchy.Key key = ExecutableHierarchy.Key.of(method);
        if (!checkedReturnValueDeclarations.contains(key)) {
            ExecutableHierarchy.checkReturnValueDeclarations(reflectionSupport, hierarchy);
            checkedReturnValueDeclarations.add(key);
        }
    }

    /**
     * The group conversions of the properties of a bean are checked once, the first time the bean
     * type is validated.
     */
    void checkBeanDeclarations(BeanIntrospection<?> introspection) {
        if (checkedBeanDeclarations.contains(introspection)) {
            // checked once, published only after every check completed: no monitor on the validation path
            return;
        }
        synchronized (checkedBeanDeclarations) {
            if (!checkedBeanDeclarations.contains(introspection)) {
                for (BeanProperty<?, ?> property : introspection.getBeanProperties()) {
                    ExecutableHierarchy.checkGroupConversions(reflectionSupport, property.asArgument());
                }
                // Publish success only after every check completes. Concurrent callers must wait.
                checkedBeanDeclarations.add(introspection);
            }
        }
    }

    /** A constraint definition is checked once after a successful check, never while in progress. */
    void checkConstraintDefinition(Class<? extends Annotation> constraintType, AnnotationValue<?> occurrence) {
        if (strictConstraintDefinitions) {
            synchronized (checkedConstraintDefinitions) {
                if (!checkedConstraintDefinitions.contains(constraintType)) {
                    reflectionSupport.checkConstraintDefinition(constraintType, occurrence);
                    checkedConstraintDefinitions.add(constraintType);
                }
            }
        }
    }

    /**
     * The introspections of the super types of a bean: its super classes, then every interface it
     * implements, the ones of the JDK and of the API left aside.
     */
    List<BeanIntrospection<?>> superIntrospections(BeanIntrospection<?> introspection) {
        return superIntrospectionsCache.computeIfAbsent(introspection, i -> {
            List<BeanIntrospection<?>> found = new ArrayList<>();
            Set<Class<?>> visited = new HashSet<>();
            Class<?> beanType = i.getBeanType();
            for (Class<?> current = superType(beanType, i);
                            current != null && current != Object.class;
                            current = superType(current, i)) {
                addSuperIntrospection(current, visited, found);
            }
            for (Class<?> current = beanType; current != null && current != Object.class; current = superType(current, i)) {
                        addInterfaceIntrospections(current, visited, found, i);
            }
            return List.copyOf(found);
        });
    }

    private void addInterfaceIntrospections(Class<?> type, Set<Class<?>> visited, List<BeanIntrospection<?>> found,
            BeanIntrospection<?> root) {
        for (Class<?> anInterface : interfaces(type, root)) {
            if (visited.add(anInterface)) {
                addSuperIntrospection(anInterface, visited, found);
                addInterfaceIntrospections(anInterface, visited, found, root);
            }
        }
    }

    private static @Nullable BeanTypeHierarchy hierarchy(Class<?> type, BeanIntrospection<?> root) {
        // the hierarchy of the root describes every type of it
        return root.getTypeHierarchy().filter(hierarchy -> hierarchy.contains(type)).orElse(null);
    }

    private @Nullable Class<?> superType(Class<?> type, BeanIntrospection<?> root) {
        var hierarchy = hierarchy(type, root);
        return hierarchy == null ? reflectionSupport.superType(type) : hierarchy.getSuperclass(type).orElse(null);
    }

    private List<Class<?>> interfaces(Class<?> type, BeanIntrospection<?> root) {
        var hierarchy = hierarchy(type, root);
        return hierarchy == null ? reflectionSupport.interfaces(type) : hierarchy.getInterfaces(type);
    }

    private void addSuperIntrospection(Class<?> type, Set<Class<?>> visited, List<BeanIntrospection<?>> found) {
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("jakarta.") || name.startsWith("javax.")) {
            return;
        }
        visited.add(type);
        beanIntrospector.findIntrospection(type).ifPresent(found::add);
    }

    /**
     * Whether a type declares class-level constraints itself: the ones it inherits are validated at
     * the level declaring them.
     */
    boolean declaresConstraints(AnnotationMetadata annotationMetadata, ClassLoader classLoader) {
        for (String name : ConstraintContainers.constraintNames(reflectionSupport, annotationMetadata, classLoader)) {
            if (ConstraintAnnotationKey.isDeclaredConstraint(annotationMetadata, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether every class-level constraint a type carries is declared by one of its super types:
     * the type then declares none itself and the super types validate theirs.
     */
    boolean inheritsAllConstraints(AnnotationMetadata annotationMetadata, List<BeanIntrospection<?>> superIntrospections, ClassLoader classLoader) {
        List<String> names = ConstraintContainers.constraintNames(reflectionSupport, annotationMetadata, classLoader);
        if (names.isEmpty() || declaresConstraints(annotationMetadata, classLoader)) {
            return false;
        }
        Set<String> superDeclared = new HashSet<>();
        for (BeanIntrospection<?> superIntrospection : superIntrospections) {
            AnnotationMetadata superMetadata = superIntrospection.getAnnotationMetadata();
            for (String name : ConstraintContainers.constraintNames(reflectionSupport, superMetadata, classLoader)) {
                if (ConstraintAnnotationKey.isDeclaredConstraint(superMetadata, name)) {
                    superDeclared.add(name);
                }
            }
        }
        return superDeclared.containsAll(names);
    }

    /**
     * An executable as the metadata providers configure it, computed once per executable.
     *
     * @param annotationMetadata The executable annotations
     * @param arguments The parameters
     * @param returnArgument The return value
     */
    record ConfiguredExecutable(AnnotationMetadata annotationMetadata, Argument<?>[] arguments, Argument<?> returnArgument) {
    }
}
