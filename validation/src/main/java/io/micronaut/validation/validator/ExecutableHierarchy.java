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
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.reflection.MethodHierarchy;
import io.micronaut.reflection.MethodHierarchy.Declaration;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.GroupSequence;
import jakarta.validation.Valid;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules the specification sets for the declarations of an executable across a type hierarchy, checked
 * over the {@link MethodHierarchy} micronaut-reflection resolves: parameter constraints, cascades and group
 * conversions are declared once at the root of the hierarchy, a return value is cascaded once, and nothing of
 * that is declared in parallel branches.
 *
 * @since 5.0.0
 */
@Internal
final class ExecutableHierarchy {

    private ExecutableHierarchy() {
    }

    /**
     * Parameter constraints, cascades and group conversions are declared once, at the root of the hierarchy.
     *
     * <p>When the declaring type is not introspected reflectively, the validated metadata may already merge
     * what the executable inherits: only what none of the inherited declarations carries counts as added.</p>
     *
     * @param hierarchy The hierarchy of the executable
     */
    static void checkParameterDeclarations(MethodHierarchy hierarchy) {
        Declaration declared = hierarchy.declared();
        List<Declaration> inherited = hierarchy.inherited();
        for (Argument<?> argument : declared.arguments()) {
            checkGroupConversions(argument);
        }
        if (inherited.isEmpty()) {
            return;
        }
        if (declared.exact() ? hasParameterConstraintsOrCascades(declared) : addsParameterConstraints(hierarchy)) {
            throw new ConstraintDeclarationException("Parameter constraints cannot be added in overriding or implementing methods: " + describe(declared));
        }
        if (hierarchy.parallel() && inherited.stream().anyMatch(ExecutableHierarchy::hasParameterConstraintsOrCascades)) {
            throw new ConstraintDeclarationException("Parallel method declarations cannot declare parameter constraints: " + describe(declared));
        }
        if (declared.exact() ? hasParameterGroupConversions(declared) : addsParameterGroupConversions(hierarchy)) {
            throw new ConstraintDeclarationException("Group conversions on parameters cannot be added in overriding or implementing methods: " + describe(declared));
        }
        if (hierarchy.parallel() && inherited.stream().anyMatch(ExecutableHierarchy::hasParameterGroupConversions)) {
            throw new ConstraintDeclarationException("Parallel method declarations cannot declare parameter group conversions: " + describe(declared));
        }
    }

    /**
     * A return value is marked cascaded once in the hierarchy, and its group conversions are not declared in parallel.
     *
     * @param hierarchy The hierarchy of the executable
     */
    static void checkReturnValueDeclarations(MethodHierarchy hierarchy) {
        Declaration declared = hierarchy.declared();
        List<Declaration> inherited = hierarchy.inherited();
        checkGroupConversions(declared.annotationMetadata(), isCascaded(declared.annotationMetadata()));
        for (Argument<?> typeArgument : declared.returnArgument().getTypeParameters()) {
            checkGroupConversions(typeArgument);
        }
        if (inherited.isEmpty()) {
            return;
        }
        long inheritedCascaded = inherited.stream().filter(ExecutableHierarchy::hasCascadedReturnValue).count();
        if (declared.exact() && hasCascadedReturnValue(declared) && inheritedCascaded > 0
            || inheritedCascaded > 1 && hasCascadedReturnConflict(inherited)) {
            throw new ConstraintDeclarationException("Return value cannot be marked cascaded more than once in a method hierarchy: " + describe(declared));
        }
        if (hierarchy.parallel() && inherited.stream().anyMatch(ExecutableHierarchy::hasReturnValueGroupConversions)) {
            throw new ConstraintDeclarationException("Parallel method declarations cannot declare return value group conversions: " + describe(declared));
        }
    }

    /**
     * Checks the group conversions of an element and of its type arguments.
     *
     * @param argument The element
     */
    static void checkGroupConversions(Argument<?> argument) {
        checkGroupConversions(argument.getAnnotationMetadata(), isCascaded(argument.getAnnotationMetadata()));
        for (Argument<?> typeArgument : argument.getTypeParameters()) {
            checkGroupConversions(typeArgument);
        }
    }

    /**
     * Group conversions are declared on cascaded elements, from a group that is not a sequence, once per source group.
     *
     * @param annotationMetadata The element annotations
     * @param cascaded           Whether the element is cascaded
     */
    static void checkGroupConversions(AnnotationMetadata annotationMetadata, boolean cascaded) {
        List<AnnotationValue<ConvertGroup>> conversions = annotationMetadata.getAnnotationValuesByType(ConvertGroup.class);
        if (conversions.isEmpty()) {
            return;
        }
        if (!cascaded) {
            throw new ConstraintDeclarationException("Group conversions can only be declared on cascaded elements");
        }
        Map<Class<?>, Class<?>> seen = new HashMap<>();
        for (AnnotationValue<ConvertGroup> conversion : conversions) {
            Class<?> from = conversion.classValue("from").orElse(Default.class);
            Class<?> to = conversion.classValue("to")
                .orElseThrow(() -> new ConstraintDeclarationException("Group conversion is missing a target group"));
            if (from.isAnnotationPresent(GroupSequence.class)) {
                throw new ConstraintDeclarationException("Group conversion source cannot be a group sequence: " + from.getName());
            }
            if (seen.putIfAbsent(from, to) != null) {
                throw new ConstraintDeclarationException("Multiple group conversions declare the same source group: " + from.getName());
            }
        }
    }

    private static boolean addsParameterConstraints(MethodHierarchy hierarchy) {
        Argument<?>[] local = hierarchy.local().arguments();
        for (int i = 0; i < local.length; i++) {
            int index = i;
            Set<String> added = new HashSet<>(constraintNames(local[i]));
            hierarchy.inherited().forEach(declaration -> added.removeAll(constraintNames(declaration.arguments()[index])));
            if (!added.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean addsParameterGroupConversions(MethodHierarchy hierarchy) {
        Argument<?>[] local = hierarchy.local().arguments();
        for (int i = 0; i < local.length; i++) {
            int index = i;
            Set<String> added = new HashSet<>(groupConversions(local[i]));
            hierarchy.inherited().forEach(declaration -> added.removeAll(groupConversions(declaration.arguments()[index])));
            if (!added.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The constraints and cascades of an argument and of its type arguments, by name.
     */
    private static Set<String> constraintNames(Argument<?> argument) {
        Set<String> names = new HashSet<>();
        collectConstraintNames(argument, "", names);
        return names;
    }

    private static void collectConstraintNames(Argument<?> argument, String prefix, Set<String> names) {
        AnnotationMetadata annotationMetadata = argument.getAnnotationMetadata();
        for (String name : ConstraintContainers.constraintNames(annotationMetadata, classLoader())) {
            names.add(prefix + name);
        }
        if (isCascaded(annotationMetadata)) {
            names.add(prefix + Valid.class.getName());
        }
        Argument<?>[] typeParameters = argument.getTypeParameters();
        for (int i = 0; i < typeParameters.length; i++) {
            collectConstraintNames(typeParameters[i], prefix + i + ":", names);
        }
    }

    /**
     * The group conversions of an argument and of its type arguments, as {@code from->to} pairs.
     */
    private static Set<String> groupConversions(Argument<?> argument) {
        Set<String> conversions = new HashSet<>();
        collectGroupConversions(argument, "", conversions);
        return conversions;
    }

    private static void collectGroupConversions(Argument<?> argument, String prefix, Set<String> conversions) {
        for (AnnotationValue<ConvertGroup> conversion : argument.getAnnotationMetadata().getAnnotationValuesByType(ConvertGroup.class)) {
            conversions.add(prefix + conversion.stringValue("from").orElse(Default.class.getName()) + "->" + conversion.stringValue("to").orElse(""));
        }
        Argument<?>[] typeParameters = argument.getTypeParameters();
        for (int i = 0; i < typeParameters.length; i++) {
            collectGroupConversions(typeParameters[i], prefix + i + ":", conversions);
        }
    }

    private static boolean hasCascadedReturnConflict(List<Declaration> declarations) {
        List<Class<?>> cascadedTypes = declarations.stream()
            .filter(ExecutableHierarchy::hasCascadedReturnValue)
            .map(Declaration::declaringType)
            .toList();
        for (int i = 0; i < cascadedTypes.size(); i++) {
            for (int j = i + 1; j < cascadedTypes.size(); j++) {
                if (cascadedTypes.get(i).isAssignableFrom(cascadedTypes.get(j)) || cascadedTypes.get(j).isAssignableFrom(cascadedTypes.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasParameterConstraintsOrCascades(Declaration declaration) {
        return Arrays.stream(declaration.arguments()).anyMatch(ExecutableHierarchy::isConstrainedOrCascaded);
    }

    private static boolean hasCascadedReturnValue(Declaration declaration) {
        return isCascaded(declaration.annotationMetadata())
            || Arrays.stream(declaration.returnArgument().getTypeParameters()).anyMatch(ExecutableHierarchy::isCascaded);
    }

    private static boolean hasParameterGroupConversions(Declaration declaration) {
        return Arrays.stream(declaration.arguments()).anyMatch(ExecutableHierarchy::hasGroupConversions);
    }

    private static boolean hasReturnValueGroupConversions(Declaration declaration) {
        return hasGroupConversions(declaration.annotationMetadata())
            || Arrays.stream(declaration.returnArgument().getTypeParameters()).anyMatch(ExecutableHierarchy::hasGroupConversions);
    }

    private static boolean isConstrainedOrCascaded(Argument<?> argument) {
        AnnotationMetadata annotationMetadata = argument.getAnnotationMetadata();
        if (ConstraintContainers.hasConstraints(annotationMetadata, classLoader()) || isCascaded(annotationMetadata)) {
            return true;
        }
        return Arrays.stream(argument.getTypeParameters()).anyMatch(ExecutableHierarchy::isConstrainedOrCascaded);
    }

    private static ClassLoader classLoader() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        return classLoader == null ? ExecutableHierarchy.class.getClassLoader() : classLoader;
    }

    private static boolean isCascaded(Argument<?> argument) {
        return isCascaded(argument.getAnnotationMetadata()) || Arrays.stream(argument.getTypeParameters()).anyMatch(ExecutableHierarchy::isCascaded);
    }

    private static boolean isCascaded(AnnotationMetadata annotationMetadata) {
        return annotationMetadata.hasStereotype(Valid.class);
    }

    private static boolean hasGroupConversions(Argument<?> argument) {
        return hasGroupConversions(argument.getAnnotationMetadata()) || Arrays.stream(argument.getTypeParameters()).anyMatch(ExecutableHierarchy::hasGroupConversions);
    }

    private static boolean hasGroupConversions(AnnotationMetadata annotationMetadata) {
        return !annotationMetadata.getAnnotationValuesByType(ConvertGroup.class).isEmpty();
    }

    private static String describe(Declaration declaration) {
        return declaration.declaringType().getName();
    }

    /**
     * The identity of an executable, stable across the executable method instances created for it.
     *
     * @param declaringType  The declaring type
     * @param name           The name
     * @param parameterTypes The parameter types
     */
    record Key(Class<?> declaringType, String name, List<Class<?>> parameterTypes) {

        static Key of(ExecutableMethod<?, ?> method) {
            return new Key(method.getDeclaringType(), method.getMethodName(), List.of(Argument.toClassArray(method.getArguments())));
        }
    }
}
