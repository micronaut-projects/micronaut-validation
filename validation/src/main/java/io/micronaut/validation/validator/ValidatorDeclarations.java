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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.validation.validator.constraints.ConstraintDefinitions;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import io.micronaut.core.type.Argument;
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the validator knows about the declarations it validates: the hierarchies of the executables, the
 * super types of the beans, and the declaration and definition rules checked once per declaration.
 *
 * @since 5.0.0
 */
@Internal
final class ValidatorDeclarations {

    private final BeanIntrospector beanIntrospector;
    private final boolean strictConstraintDefinitions;
    private final Map<ExecutableHierarchy.Key, ExecutableHierarchy.Resolved> executableHierarchies = new ConcurrentHashMap<>();
    private final Set<Class<?>> checkedConstraintDefinitions = ConcurrentHashMap.newKeySet();
    private final Set<BeanIntrospection<?>> checkedBeanDeclarations = ConcurrentHashMap.newKeySet();
    private final Map<BeanIntrospection<?>, List<BeanIntrospection<?>>> superIntrospectionsCache = new ConcurrentHashMap<>();
    private final Map<ExecutableHierarchy.Key, ConfiguredExecutable> configuredExecutables = new ConcurrentHashMap<>();

    private final List<ValidationMetadataProvider> metadataProviders;

    ValidatorDeclarations(BeanIntrospector beanIntrospector, boolean strictConstraintDefinitions, List<ValidationMetadataProvider> metadataProviders) {
        this.beanIntrospector = beanIntrospector;
        this.strictConstraintDefinitions = strictConstraintDefinitions;
        this.metadataProviders = metadataProviders;
    }

    /**
     * The hierarchy of a bean method, for the descriptors of a bean.
     */
    ExecutableHierarchy.Resolved resolveHierarchy(BeanMethod<?, ?> method) {
        return ExecutableHierarchy.resolve(beanIntrospector, ExecutableHierarchy.Declaration.of(method, false), method.getName());
    }

    /**
     * The argument of a property as the metadata providers configure it.
     */
    Argument<?> configuredPropertyArgument(Class<?> beanType, String propertyName, Argument<?> argument) {
        for (ValidationMetadataProvider provider : metadataProviders) {
            argument = provider.getPropertyArgument(beanType, propertyName, argument);
        }
        return argument;
    }

    /**
     * A method with what it inherits and what the metadata providers configure for it.
     */
    ConfiguredExecutable configuredExecutable(ExecutableMethod<?, ?> method, ExecutableHierarchy.Resolved hierarchy) {
        return configuredExecutables.computeIfAbsent(ExecutableHierarchy.Key.of(method), key -> new ConfiguredExecutable(
            configuredMethodMetadata(method, hierarchy.annotationMetadata()),
            configuredParameterArguments(method, hierarchy.arguments()),
            configuredReturnArgument(method, hierarchy.returnArgument())
        ));
    }

    /**
     * A constructor as the metadata providers configure it, computed once per constructor.
     */
    ConfiguredExecutable configuredConstructor(Class<?> beanType, AnnotationMetadata annotationMetadata, Argument<?>[] arguments) {
        ExecutableHierarchy.Key key = new ExecutableHierarchy.Key(beanType, "<init>", List.of(Argument.toClassArray(arguments)));
        return configuredExecutables.computeIfAbsent(key, ignored -> {
            AnnotationMetadata metadata = configuredConstructorMetadata(beanType, arguments, annotationMetadata);
            return new ConfiguredExecutable(
                metadata,
                configuredConstructorArguments(beanType, arguments),
                configuredConstructorReturnArgument(beanType, arguments, Argument.of(beanType, metadata))
            );
        });
    }

    /**
     * The parameters of a method as the metadata providers configure them.
     */
    private Argument<?>[] configuredParameterArguments(ExecutableMethod<?, ?> method, Argument<?>[] arguments) {
        for (ValidationMetadataProvider provider : metadataProviders) {
            arguments = provider.getMethodParameterArguments(method.getDeclaringType(), method.getMethodName(), arguments);
        }
        return arguments;
    }

    /**
     * The annotations of a method as the metadata providers configure them.
     */
    private AnnotationMetadata configuredMethodMetadata(ExecutableMethod<?, ?> method, AnnotationMetadata annotationMetadata) {
        Class<?>[] parameterTypes = Argument.toClassArray(method.getArguments());
        for (ValidationMetadataProvider provider : metadataProviders) {
            annotationMetadata = provider.getMethodAnnotationMetadata(method.getDeclaringType(), method.getMethodName(), parameterTypes, annotationMetadata);
        }
        return annotationMetadata;
    }

    /**
     * The return value of a method as the metadata providers configure it.
     */
    private Argument<?> configuredReturnArgument(ExecutableMethod<?, ?> method, Argument<?> argument) {
        Class<?>[] parameterTypes = Argument.toClassArray(method.getArguments());
        for (ValidationMetadataProvider provider : metadataProviders) {
            argument = provider.getMethodReturnArgument(method.getDeclaringType(), method.getMethodName(), parameterTypes, argument);
        }
        return argument;
    }

    /**
     * The parameters of a constructor as the metadata providers configure them.
     */
    private Argument<?>[] configuredConstructorArguments(Class<?> beanType, Argument<?>[] arguments) {
        for (ValidationMetadataProvider provider : metadataProviders) {
            arguments = provider.getConstructorParameterArguments(beanType, arguments);
        }
        return arguments;
    }

    /**
     * The annotations of a constructor as the metadata providers configure them.
     */
    private AnnotationMetadata configuredConstructorMetadata(Class<?> beanType, Argument<?>[] arguments, AnnotationMetadata annotationMetadata) {
        Class<?>[] parameterTypes = Argument.toClassArray(arguments);
        for (ValidationMetadataProvider provider : metadataProviders) {
            annotationMetadata = provider.getConstructorAnnotationMetadata(beanType, parameterTypes, annotationMetadata);
        }
        return annotationMetadata;
    }

    /**
     * The return value of a constructor as the metadata providers configure it.
     */
    private Argument<?> configuredConstructorReturnArgument(Class<?> beanType, Argument<?>[] arguments, Argument<?> argument) {
        Class<?>[] parameterTypes = Argument.toClassArray(arguments);
        for (ValidationMetadataProvider provider : metadataProviders) {
            argument = provider.getConstructorReturnArgument(beanType, parameterTypes, argument);
        }
        return argument;
    }

    ExecutableHierarchy.Resolved resolveHierarchy(ExecutableMethod<?, ?> method) {
        return executableHierarchies.computeIfAbsent(ExecutableHierarchy.Key.of(method),
            key -> ExecutableHierarchy.resolve(beanIntrospector, ExecutableHierarchy.Declaration.of(method), method.getMethodName()));
    }

    /**
     * The group conversions of the properties of a bean are checked once, the first time the bean type is validated.
     */
    void checkBeanDeclarations(BeanIntrospection<?> introspection) {
        if (checkedBeanDeclarations.add(introspection)) {
            try {
                for (BeanProperty<?, ?> property : introspection.getBeanProperties()) {
                    ExecutableHierarchy.checkGroupConversions(property.asArgument());
                }
            } catch (RuntimeException e) {
                checkedBeanDeclarations.remove(introspection);
                throw e;
            }
        }
    }

    /**
     * A constraint definition is checked once, the first time the constraint is found.
     */
    void checkConstraintDefinition(Class<? extends Annotation> constraintType) {
        if (strictConstraintDefinitions && checkedConstraintDefinitions.add(constraintType)) {
            try {
                ConstraintDefinitions.validate(constraintType);
            } catch (RuntimeException e) {
                checkedConstraintDefinitions.remove(constraintType);
                throw e;
            }
        }
    }

    /**
     * The introspections of the super types of a bean: its super classes, then every interface it implements,
     * the ones of the JDK and of the API left aside.
     */
    List<BeanIntrospection<?>> superIntrospections(BeanIntrospection<?> introspection) {
        return superIntrospectionsCache.computeIfAbsent(introspection, i -> {
            List<BeanIntrospection<?>> found = new ArrayList<>();
            Set<Class<?>> visited = new HashSet<>();
            Class<?> beanType = i.getBeanType();
            for (Class<?> current = beanType.getSuperclass(); current != null && current != Object.class; current = current.getSuperclass()) {
                addSuperIntrospection(current, visited, found);
            }
            for (Class<?> current = beanType; current != null && current != Object.class; current = current.getSuperclass()) {
                addInterfaceIntrospections(current, visited, found);
            }
            return List.copyOf(found);
        });
    }

    private void addInterfaceIntrospections(Class<?> type, Set<Class<?>> visited, List<BeanIntrospection<?>> found) {
        for (Class<?> anInterface : type.getInterfaces()) {
            if (visited.add(anInterface)) {
                addSuperIntrospection(anInterface, visited, found);
                addInterfaceIntrospections(anInterface, visited, found);
            }
        }
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
     * Whether a type declares class-level constraints itself: the ones it inherits are validated at the level
     * declaring them.
     */
    boolean declaresConstraints(AnnotationMetadata annotationMetadata, ClassLoader classLoader) {
        Set<String> declared = annotationMetadata.getDeclaredAnnotationNames();
        for (String name : ConstraintContainers.constraintNames(annotationMetadata, classLoader)) {
            if (ConstraintAnnotationKey.isDeclaredConstraint(declared, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether every class-level constraint a type carries is declared by one of its super types: the type
     * then declares none itself and the super types validate theirs.
     */
    boolean inheritsAllConstraints(AnnotationMetadata annotationMetadata, List<BeanIntrospection<?>> superIntrospections, ClassLoader classLoader) {
        List<String> names = ConstraintContainers.constraintNames(annotationMetadata, classLoader);
        if (names.isEmpty() || declaresConstraints(annotationMetadata, classLoader)) {
            return false;
        }
        Set<String> superDeclared = new HashSet<>();
        for (BeanIntrospection<?> superIntrospection : superIntrospections) {
            AnnotationMetadata superMetadata = superIntrospection.getAnnotationMetadata();
            Set<String> declared = superMetadata.getDeclaredAnnotationNames();
            for (String name : ConstraintContainers.constraintNames(superMetadata, classLoader)) {
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
     * @param arguments          The parameters
     * @param returnArgument     The return value
     */
    record ConfiguredExecutable(AnnotationMetadata annotationMetadata, Argument<?>[] arguments, Argument<?> returnArgument) {
    }
}
