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
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import io.micronaut.validation.validator.metadata.ValidationMetadataSupport;
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
    private final BeanIntrospector beanIntrospector;
    private final boolean strictConstraintDefinitions;
    private final Map<ExecutableHierarchy.Key, ExecutableHierarchy.Resolved> executableHierarchies = new ConcurrentHashMap<>();
    private final Set<Class<?>> checkedConstraintDefinitions = ConcurrentHashMap.newKeySet();
    private final Set<BeanIntrospection<?>> checkedBeanDeclarations = ConcurrentHashMap.newKeySet();
    private final Map<BeanIntrospection<?>, List<BeanIntrospection<?>>> superIntrospectionsCache = new ConcurrentHashMap<>();
    private final Map<ExecutableHierarchy.Key, ConfiguredExecutable> configuredExecutables = new ConcurrentHashMap<>();

    ValidatorDeclarations(BeanIntrospector beanIntrospector, boolean strictConstraintDefinitions, ReflectionSupport reflectionSupport) {
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
        superIntrospectionsCache.clear();
        configuredExecutables.clear();
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

    /** A constructor as its introspection describes it, computed once per constructor. */
    ConfiguredExecutable configuredConstructor(Class<?> beanType, AnnotationMetadata annotationMetadata, Argument<?>[] arguments) {
        ExecutableHierarchy.Key key = new ExecutableHierarchy.Key(beanType, "<init>", List.of(Argument.toClassArray(arguments)));
        return configuredExecutables.computeIfAbsent(key, ignored -> new ConfiguredExecutable(
            annotationMetadata,
            arguments,
            Argument.of(beanType, annotationMetadata)
        ));
    }

    ExecutableHierarchy.Resolved resolveHierarchy(ExecutableMethod<?, ?> method) {
        return executableHierarchies.computeIfAbsent(ExecutableHierarchy.Key.of(method),
            key -> reflectionSupport.resolveHierarchy(beanIntrospector, ExecutableHierarchy.Declaration.of(method), method.getMethodName()));
    }

    /**
     * The group conversions of the properties of a bean are checked once, the first time the bean
     * type is validated.
     */
    void checkBeanDeclarations(BeanIntrospection<?> introspection) {
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

    private static @Nullable AnnotationValue<Annotation> hierarchy(
            Class<?> type, BeanIntrospection<?> root) {
        return ValidationMetadataSupport.hierarchy(root.getAnnotationMetadata(), type);
    }

    private @Nullable Class<?> superType(Class<?> type, BeanIntrospection<?> root) {
        var metadata = hierarchy(type, root);
        return metadata == null ? reflectionSupport.superType(type) : metadata.classValue("superType").orElse(null);
    }

    private List<Class<?>> interfaces(Class<?> type, BeanIntrospection<?> root) {
        var metadata = hierarchy(type, root);
        return metadata == null ? reflectionSupport.interfaces(type) : List.of(metadata.classValues("interfaces"));
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
        Set<String> declared = annotationMetadata.getDeclaredAnnotationNames();
        for (String name : ConstraintContainers.constraintNames(reflectionSupport, annotationMetadata, classLoader)) {
            if (ConstraintAnnotationKey.isDeclaredConstraint(declared, name)) {
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
            Set<String> declared = superMetadata.getDeclaredAnnotationNames();
            for (String name : ConstraintContainers.constraintNames(reflectionSupport, superMetadata, classLoader)) {
                if (ConstraintAnnotationKey.isDeclaredConstraint(declared, name)) {
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
