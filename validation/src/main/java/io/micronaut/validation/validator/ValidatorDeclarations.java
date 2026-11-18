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
import io.micronaut.core.util.CopyOnWriteMap;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import jakarta.validation.GroupSequence;
import jakarta.validation.ParameterNameProvider;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * What the validator knows about the declarations it validates: the hierarchies of the executables,
 * the super types of the beans, and the declaration and definition rules checked once per
 * declaration.
 *
 * @since 5.3.0
 */
@Internal
final class ValidatorDeclarations {

    private static final int MAX_METHOD_PLANS = 4096;

    private final ReflectionSupport reflectionSupport;
    // the provider of the application naming the parameters, null for the names compiled into the metadata
    private final @Nullable ParameterNameProvider parameterNameProvider;
    private final BeanIntrospector beanIntrospector;
    private final boolean strictConstraintDefinitions;
    private final Map<ExecutableHierarchy.Key, ExecutablePlan> executablePlans = new ConcurrentHashMap<>();
    // bounded: a method described per call, reflectively, or groups requested anew, are not kept past it
    private final Map<ExecutableMethod<?, ?>, ExecutablePlan> methodPlans = new ConcurrentHashMap<>();
    private final Map<GroupSequenceKey, List<DefaultConstraintValidatorContext.ValidationGroup>> requestedGroupSequences = new ConcurrentHashMap<>();
    // keyed by identity: the bean methods of the introspection the validator reads
    private final Map<BeanMethod<?, ?>, ExecutableHierarchy.Resolved> beanMethodHierarchies = new ConcurrentHashMap<>();
    private final Set<Class<?>> checkedConstraintDefinitions = ConcurrentHashMap.newKeySet();
    private final Set<BeanIntrospection<?>> checkedBeanDeclarations = ConcurrentHashMap.newKeySet();
    private final Map<BeanIntrospection<?>, List<BeanIntrospection<?>>> superIntrospectionsCache = new ConcurrentHashMap<>();
    private final Map<BeanIntrospection<?>, BeanPlan> beanPlans = new ConcurrentHashMap<>();
    // a group and the groups it extends, transitively, the group first
    private final Map<Class<?>, List<Class<?>>> inheritedGroups = new ConcurrentHashMap<>();
    private final Map<ExecutableHierarchy.Key, ConfiguredExecutable> configuredExecutables = new ConcurrentHashMap<>();
    private final Map<BeanIntrospection<?>, List<DefaultConstraintValidatorContext.ValidationGroup>> defaultGroupSequences = CopyOnWriteMap.create(16 * 1024);

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

    /**
     * @return The provider of the application naming the parameters, null for the names compiled into the metadata
     */
    @Nullable ParameterNameProvider parameterNameProvider() {
        return parameterNameProvider;
    }

    ReflectionSupport reflectionSupport() {
        return reflectionSupport;
    }

    void clear() {
        executablePlans.clear();
        methodPlans.clear();
        requestedGroupSequences.clear();
        beanMethodHierarchies.clear();
        checkedConstraintDefinitions.clear();
        checkedBeanDeclarations.clear();
        superIntrospectionsCache.clear();
        beanPlans.clear();
        configuredExecutables.clear();
        defaultGroupSequences.clear();
        inheritedGroups.clear();
    }

    /**
     * @param group The group
     * @return The group and the groups it extends, transitively, read once per group
     */
    List<Class<?>> inheritedGroups(Class<?> group) {
        List<Class<?>> groups = inheritedGroups.get(group);
        if (groups == null) {
            List<Class<?>> collected = new ArrayList<>();
            GroupSequences.addInheritedGroups(reflectionSupport, group, collected);
            groups = List.copyOf(collected);
            inheritedGroups.put(group, groups);
        }
        return groups;
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

    /**
     * @param declaringType The type declaring the constructor
     * @param arguments The arguments of the constructor
     * @return The names the parameter name provider gives the parameters, null without one or without the constructor
     */
    @Nullable List<String> parameterNames(Class<?> declaringType, Argument<?>[] arguments) {
        if (parameterNameProvider == null) {
            return null;
        }
        java.lang.reflect.Constructor<?> constructor = reflectionSupport.declaredConstructor(declaringType, Argument.toClassArray(arguments));
        return constructor == null ? null : parameterNameProvider.getParameterNames(constructor);
    }

    /** The hierarchy of a bean method, for the descriptors of a bean. */
    ExecutableHierarchy.Resolved resolveHierarchy(BeanMethod<?, ?> method) {
        return beanMethodHierarchies.computeIfAbsent(method, m -> reflectionSupport.resolveHierarchy(beanIntrospector,
            ExecutableHierarchy.Declaration.of(m, m.getDeclaringBean().separatesDeclarations()), m.getName()));
    }

    /**
     * What validating a method reads from its hierarchy, prepared once per method.
     *
     * @param method The method
     * @return The plan
     */
    ExecutablePlan executablePlan(ExecutableMethod<?, ?> method) {
        // by the method itself first: a bean definition hands out the same instance, which needs no key built
        ExecutablePlan plan = methodPlans.get(method);
        if (plan != null) {
            return plan;
        }
        ExecutableHierarchy.Key key = ExecutableHierarchy.Key.of(method);
        plan = executablePlans.get(key);
        if (plan == null) {
            plan = executablePlans.computeIfAbsent(key, k -> {
                ExecutableHierarchy.Resolved hierarchy = reflectionSupport.resolveHierarchy(beanIntrospector,
                    ExecutableHierarchy.Declaration.of(method), method.getMethodName());
                ConfiguredExecutable configured = new ConfiguredExecutable(hierarchy.annotationMetadata(), hierarchy.arguments(),
                    hierarchy.returnArgument(), crossParameterArgument(hierarchy.annotationMetadata()));
                return new ExecutablePlan(hierarchy, configured, configured.crossParameterArgument());
            });
        }
        // a method described per call, reflectively, is not kept past a bound
        if (methodPlans.size() >= MAX_METHOD_PLANS) {
            methodPlans.clear();
        }
        methodPlans.put(method, plan);
        return plan;
    }

    /**
     * The argument the cross-parameter constraints of an executable validate the parameters as, null without such
     * constraints.
     *
     * @param annotationMetadata The executable annotations
     * @return The argument or null
     */
    @Nullable Argument<Object[]> crossParameterArgument(AnnotationMetadata annotationMetadata) {
        return ConstraintContainers.hasConstraints(reflectionSupport, annotationMetadata, reflectionSupport.classLoader())
            ? Argument.of(Object[].class, annotationMetadata)
            : null;
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
        // checked before it is kept: a failed check fails every validation of the constructor
        for (Argument<?> argument : arguments) {
            ExecutableHierarchy.checkGroupConversions(reflectionSupport, argument);
        }
        configured = new ConfiguredExecutable(annotationMetadata, arguments, Argument.of(beanType, annotationMetadata),
            crossParameterArgument(annotationMetadata));
        if (annotationMetadata != AnnotationMetadata.EMPTY_METADATA) {
            configuredExecutables.put(key, configured);
        }
        return configured;
    }

    ExecutableHierarchy.Resolved resolveHierarchy(ExecutableMethod<?, ?> method) {
        return executablePlan(method).hierarchy();
    }

    /**
     * The parameters a hierarchy declares are checked the first time the method is validated, a failed check
     * failing every validation of it.
     */
    void checkParameterDeclarations(ExecutablePlan plan) {
        if (!plan.parametersChecked) {
            ExecutableHierarchy.checkParameterDeclarations(reflectionSupport, plan.hierarchy());
            plan.parametersChecked = true;
        }
    }

    /**
     * The return value a hierarchy declares is checked the first time the method is validated, a failed check
     * failing every validation of it.
     */
    void checkReturnValueDeclarations(ExecutablePlan plan) {
        if (!plan.returnValueChecked) {
            ExecutableHierarchy.checkReturnValueDeclarations(reflectionSupport, plan.hierarchy());
            plan.returnValueChecked = true;
        }
    }

    /**
     * The group conversions of the properties of a bean are checked once, the first time the bean
     * type is validated.
     */
    void checkBeanDeclarations(BeanIntrospection<?> introspection) {
        checkOnce(checkedBeanDeclarations, introspection, () -> {
            for (BeanProperty<?, ?> property : introspection.getBeanProperties()) {
                ExecutableHierarchy.checkGroupConversions(reflectionSupport, property.asArgument());
            }
        });
    }

    /** A constraint definition is checked until a check succeeds. */
    void checkConstraintDefinition(Class<? extends Annotation> constraintType, AnnotationValue<?> occurrence) {
        if (strictConstraintDefinitions) {
            checkOnce(checkedConstraintDefinitions, constraintType,
                () -> reflectionSupport.checkConstraintDefinition(constraintType, occurrence));
        }
    }

    /**
     * Runs a check until it succeeds once: the key is recorded only after the check completes, so a failed
     * check fails every call, and callers racing on the first check each run it.
     */
    private static <K> void checkOnce(Set<K> checked, K key, Runnable check) {
        if (!checked.contains(key)) {
            check.run();
            checked.add(key);
        }
    }

    /**
     * The group sequences the default group expands to for a bean, computed once per bean.
     *
     * @param introspection The bean, null for a value without one
     * @param resolver Computes the sequences
     * @return The sequences
     */
    List<DefaultConstraintValidatorContext.ValidationGroup> defaultGroupSequences(@Nullable BeanIntrospection<?> introspection,
                                                                                Function<@Nullable BeanIntrospection<?>, List<DefaultConstraintValidatorContext.ValidationGroup>> resolver) {
        return defaultGroupSequences.computeIfAbsent(introspection, resolver);
    }

    /**
     * The steps groups requested without a conversion expand to for a bean, resolved once per bean and groups.
     *
     * @param validator The validator
     * @param introspection The bean, null for the groups alone
     * @param groups The requested groups
     * @return The steps
     */
    List<DefaultConstraintValidatorContext.ValidationGroup> groupSequences(DefaultValidator validator,
                                                                         @Nullable BeanIntrospection<?> introspection,
                                                                         List<Class<?>> groups) {
        if (groups == DefaultConstraintValidatorContext.DEFAULT_GROUPS) {
            List<DefaultConstraintValidatorContext.ValidationGroup> found = defaultGroupSequences.get(introspection);
            if (found != null) {
                return found;
            }
            return defaultGroupSequences.computeIfAbsent(introspection, bi -> resolveGroupSequences(validator, bi, groups));
        }
        GroupSequenceKey key = new GroupSequenceKey(introspection, groups);
        List<DefaultConstraintValidatorContext.ValidationGroup> found = requestedGroupSequences.get(key);
        if (found == null) {
            // a failed resolution is not kept: it fails again
            found = resolveGroupSequences(validator, introspection, groups);
            if (requestedGroupSequences.size() >= MAX_METHOD_PLANS) {
                requestedGroupSequences.clear();
            }
            requestedGroupSequences.put(new GroupSequenceKey(introspection, List.copyOf(groups)), found);
        }
        return found;
    }

    private static List<DefaultConstraintValidatorContext.ValidationGroup> resolveGroupSequences(DefaultValidator validator,
                                                                                               @Nullable BeanIntrospection<?> introspection,
                                                                                               List<Class<?>> groups) {
        var ctx = new GroupSequences.FindGroupContext(validator, Map.of(), groups);
        return List.copyOf(introspection == null ? GroupSequences.findGroupSequences(ctx) : GroupSequences.findGroupSequences(ctx, introspection));
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
        if (ValidationAnnotationUtil.isPlatformType(name)) {
            return;
        }
        visited.add(type);
        beanIntrospector.findIntrospection(type).ifPresent(found::add);
    }

    /**
     * What validating a bean reads from its type and its super types, prepared once per bean type.
     *
     * @param introspection The bean
     * @return The plan
     */
    BeanPlan beanPlan(BeanIntrospection<?> introspection) {
        BeanPlan plan = beanPlans.get(introspection);
        if (plan != null) {
            return plan;
        }
        return beanPlans.computeIfAbsent(introspection, i -> {
            ClassLoader classLoader = reflectionSupport.classLoader();
            List<BeanIntrospection<?>> supers = superIntrospections(i);
            AnnotationMetadata annotationMetadata = i.getAnnotationMetadata();
            List<BeanIntrospection<?>> constrainedSupers = new ArrayList<>(supers.size());
            Map<Class<?>, Class<Object>[]> redefinitions = new LinkedHashMap<>();
            Map<Class<?>, Argument<?>> superArguments = new HashMap<>();
            for (BeanIntrospection<?> superIntrospection : supers) {
                if (declaresConstraints(superIntrospection.getAnnotationMetadata(), classLoader)) {
                    constrainedSupers.add(superIntrospection);
                    superArguments.put(superIntrospection.getBeanType(),
                        Argument.of(superIntrospection.getBeanType(), superIntrospection.getAnnotationMetadata()));
                }
                Class<?> superType = superIntrospection.getBeanType();
                Class<Object>[] sequence = superIntrospection.getAnnotationMetadata().classValues(GroupSequence.class);
                if (!superType.isInterface() && sequence.length > 0) {
                    redefinitions.put(superType, sequence);
                }
            }
            return new BeanPlan(
                supers,
                !inheritsAllConstraints(annotationMetadata, supers, classLoader),
                List.copyOf(constrainedSupers),
                annotationMetadata.classValues(GroupSequence.class).length > 0,
                Collections.unmodifiableMap(redefinitions),
                Argument.of(i.getBeanType(), annotationMetadata),
                Map.copyOf(superArguments)
            );
        });
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
     * What validating a bean reads from its type and its super types.
     *
     * @param superIntrospections The introspections of the super types, see {@link #superIntrospections(BeanIntrospection)}
     * @param ownClassConstraints Whether the type declares class-level constraints its super types do not
     * @param constrainedSuperIntrospections The super types declaring class-level constraints themselves
     * @param redefinesDefaultGroupSequence Whether the type redefines the default group sequence
     * @param superSequenceRedefinitions The default group sequences the super classes redefine, by the class
     * @param beanArgument The bean as the element its class-level constraints are validated on
     * @param constrainedSuperArguments The constrained super types as such elements, by the class
     */
    record BeanPlan(List<BeanIntrospection<?>> superIntrospections,
                    boolean ownClassConstraints,
                    List<BeanIntrospection<?>> constrainedSuperIntrospections,
                    boolean redefinesDefaultGroupSequence,
                    Map<Class<?>, Class<Object>[]> superSequenceRedefinitions,
                    Argument<?> beanArgument,
                    Map<Class<?>, Argument<?>> constrainedSuperArguments) {
    }

    /**
     * An executable as the metadata providers configure it, computed once per executable.
     *
     * @param annotationMetadata The executable annotations
     * @param arguments The parameters
     * @param returnArgument The return value
     * @param crossParameterArgument The argument its cross-parameter constraints validate the parameters as, null
     * without such constraints
     */
    record ConfiguredExecutable(AnnotationMetadata annotationMetadata, Argument<?>[] arguments, Argument<?> returnArgument,
                                @Nullable Argument<Object[]> crossParameterArgument) {
    }

    /**
     * What validating a method reads from its hierarchy, prepared once per method. A check is recorded only after
     * it completes, so a failed check fails every validation of the method.
     */
    static final class ExecutablePlan {

        private final ExecutableHierarchy.Resolved hierarchy;
        private final ConfiguredExecutable configured;
        private final @Nullable Argument<Object[]> crossParameterArgument;
        private volatile boolean parametersChecked;
        private volatile boolean returnValueChecked;
        // the Java method a parameter name provider is handed, resolved once: absent without the reflection module
        private volatile java.lang.reflect.@Nullable Method targetMethod;
        private volatile boolean targetMethodResolved;

        ExecutablePlan(ExecutableHierarchy.Resolved hierarchy, ConfiguredExecutable configured, @Nullable Argument<Object[]> crossParameterArgument) {
            this.hierarchy = hierarchy;
            this.configured = configured;
            this.crossParameterArgument = crossParameterArgument;
        }

        ExecutableHierarchy.Resolved hierarchy() {
            return hierarchy;
        }

        ConfiguredExecutable configured() {
            return configured;
        }

        /** @return The argument the cross-parameter constraints validate the parameters as, null without any */
        @Nullable Argument<Object[]> crossParameterArgument() {
            return crossParameterArgument;
        }

        java.lang.reflect.@Nullable Method targetMethod(java.util.function.Supplier<java.lang.reflect.@Nullable Method> resolver) {
            if (!targetMethodResolved) {
                targetMethod = resolver.get();
                targetMethodResolved = true;
            }
            return targetMethod;
        }
    }

    /**
     * @param introspection The bean, null for the groups alone
     * @param groups The requested groups
     */
    private record GroupSequenceKey(@Nullable BeanIntrospection<?> introspection, List<Class<?>> groups) {
    }
}
