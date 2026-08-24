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
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.inject.reflection.ReflectiveIntrospection;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import io.micronaut.validation.validator.metadata.ConfiguredMetadata;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.GroupSequence;
import jakarta.validation.Valid;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The declarations of an executable across the type hierarchy: the constraints a method inherits from the methods
 * it overrides or implements, and the rules the specification sets for such hierarchies.
 *
 * <p>The hierarchy is read from the bean introspections of the super types, so it is as complete as the
 * introspections are: a {@link ReflectiveIntrospection} tells which annotations a type itself declares, a
 * generated introspection reports the merged metadata of the methods it lists.</p>
 *
 * @since 5.0.0
 */
@Internal
final class ExecutableHierarchy {

    private ExecutableHierarchy() {
    }

    /**
     * Resolves the hierarchy of an executable.
     *
     * @param introspector The introspector of the super types
     * @param local        The executable as validated
     * @param name         Its name
     * @return The executable with what it inherits merged in
     */
    static Resolved resolve(BeanIntrospector introspector, Declaration local, String name) {
        Class<?>[] parameterTypes = Argument.toClassArray(local.arguments());
        Declaration declared = declaredBy(introspector, local.declaringType(), name, parameterTypes).filter(Declaration::exact).orElse(local);
        List<Declaration> inherited = inherited(introspector, local.declaringType(), name, parameterTypes);
        if (inherited.isEmpty()) {
            return new Resolved(local, declared, inherited, local.annotationMetadata(), local.arguments(), local.returnArgument());
        }
        // the farthest declaration first, the validated one last: it wins where the same annotation is repeated
        List<Declaration> levels = new ArrayList<>(inherited);
        java.util.Collections.reverse(levels);
        levels.add(local);
        Argument<?>[] arguments = new Argument[local.arguments().length];
        for (int i = 0; i < arguments.length; i++) {
            int index = i;
            arguments[i] = mergeArgument(levels.stream().map(level -> level.arguments()[index]).toList());
        }
        return new Resolved(local,
            declared,
            inherited,
            merge(levels.stream().map(Declaration::annotationMetadata).toList()),
            arguments,
            mergeArgument(levels.stream().map(Declaration::returnArgument).toList()));
    }

    /**
     * The declarations an executable overrides or implements: the ones of the super classes, then of all the
     * interfaces, each interface visited once.
     */
    private static List<Declaration> inherited(BeanIntrospector introspector, Class<?> declaringType, String name, Class<?>[] parameterTypes) {
        List<Declaration> declarations = new ArrayList<>();
        Set<Class<?>> visitedInterfaces = new HashSet<>();
        for (Class<?> current = declaringType.getSuperclass(); current != null && current != Object.class; current = current.getSuperclass()) {
            declaredBy(introspector, current, name, parameterTypes).ifPresent(declarations::add);
            collectInterfaceDeclarations(introspector, current, name, parameterTypes, visitedInterfaces, declarations);
        }
        collectInterfaceDeclarations(introspector, declaringType, name, parameterTypes, visitedInterfaces, declarations);
        return declarations;
    }

    private static void collectInterfaceDeclarations(BeanIntrospector introspector,
                                                     Class<?> type,
                                                     String name,
                                                     Class<?>[] parameterTypes,
                                                     Set<Class<?>> visitedInterfaces,
                                                     List<Declaration> declarations) {
        for (Class<?> interfaceType : type.getInterfaces()) {
            if (visitedInterfaces.add(interfaceType)) {
                declaredBy(introspector, interfaceType, name, parameterTypes).ifPresent(declarations::add);
                collectInterfaceDeclarations(introspector, interfaceType, name, parameterTypes, visitedInterfaces, declarations);
            }
        }
    }

    /**
     * The declaration of a method by a type itself, read from the introspection of the type.
     */
    private static Optional<Declaration> declaredBy(BeanIntrospector introspector, Class<?> type, String name, Class<?>[] parameterTypes) {
        String typeName = type.getName();
        if (typeName.startsWith("java.") || typeName.startsWith("jakarta.")) {
            return Optional.empty();
        }
        Optional<BeanIntrospection<Object>> introspection = introspector.findIntrospection((Class<Object>) type);
        if (introspection.isEmpty()) {
            return Optional.empty();
        }
        if (introspection.get() instanceof ReflectiveIntrospection<Object> reflective) {
            return reflective.findDeclaredMethod(name, parameterTypes).map(method -> Declaration.of(method, true));
        }
        return introspection.get().getBeanMethods().stream()
            .filter(method -> method.getName().equals(name)
                && method.getDeclaringType() == type
                && Arrays.equals(Argument.toClassArray(method.getArguments()), parameterTypes))
            .findFirst()
            .map(method -> Declaration.of(method, false));
    }

    /**
     * Merges the annotations of the levels of an argument, type arguments included, the last level winning.
     *
     * @param levels The levels, the validated one last
     * @return The merged argument
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Argument<?> mergeArgument(List<Argument<?>> levels) {
        Argument<?> local = levels.get(levels.size() - 1);
        Argument<?>[] localTypeParameters = local.getTypeParameters();
        Argument<?>[] typeParameters = new Argument[localTypeParameters.length];
        for (int i = 0; i < typeParameters.length; i++) {
            int index = i;
            typeParameters[i] = mergeArgument(levels.stream()
                .filter(level -> level.getTypeParameters().length == localTypeParameters.length)
                .map(level -> level.getTypeParameters()[index])
                .toList());
        }
        return Argument.of((Class) local.getType(), local.getName(), merge(levels.stream().map(Argument::getAnnotationMetadata).toList()), typeParameters);
    }

    /**
     * Merges the annotations of the levels of a hierarchy into one metadata, all of it declared.
     */
    static AnnotationMetadata merge(List<AnnotationMetadata> levels) {
        return ConfiguredMetadata.merge(levels);
    }

    /**
     * The annotations declared on the executable, without the ones of its class: the executable methods of
     * beans carry both. A metadata that is not a hierarchy is returned as is, {@code getDeclaredMetadata()}
     * would drop the repeated annotations.
     */
    static AnnotationMetadata declaredOf(AnnotationMetadata annotationMetadata) {
        if (annotationMetadata instanceof AnnotationMetadataHierarchy hierarchy) {
            AnnotationMetadata declared = hierarchy.getDeclaredMetadata();
            return declared instanceof AnnotationMetadataHierarchy
                ? new AnnotationMetadataHierarchy(hierarchy.getRootMetadata(), declared.getDeclaredMetadata())
                : declared;
        }
        return annotationMetadata;
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
            .filter(Declaration::hasCascadedReturnValue)
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

    /**
     * One declaration of an executable in the hierarchy.
     *
     * @param declaringType      The type declaring it
     * @param annotationMetadata The executable annotations
     * @param arguments          The parameters
     * @param returnArgument     The return value
     * @param exact              Whether the annotations are the ones of this declaration only: a generated
     *                           introspection merges the annotations of the overridden methods into them
     */
    record Declaration(Class<?> declaringType,
                       AnnotationMetadata annotationMetadata,
                       Argument<?>[] arguments,
                       Argument<?> returnArgument,
                       boolean exact) {

        static Declaration of(ExecutableMethod<?, ?> method) {
            return new Declaration(method.getDeclaringType(),
                declaredOf(method.getAnnotationMetadata()),
                method.getArguments(),
                returnArgument(method.getReturnType()),
                false);
        }

        static Declaration of(BeanMethod<?, ?> method, boolean exact) {
            return new Declaration(method.getDeclaringType(),
                declaredOf(method.getAnnotationMetadata()),
                method.getArguments(),
                returnArgument(method.getReturnType()),
                exact);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Argument<?> returnArgument(ReturnType<?> returnType) {
            return Argument.of((Class) returnType.getType(), declaredOf(returnType.asArgument().getAnnotationMetadata()), returnType.getTypeParameters());
        }

        boolean hasParameterConstraintsOrCascades() {
            return Arrays.stream(arguments).anyMatch(ExecutableHierarchy::isConstrainedOrCascaded);
        }

        boolean hasCascadedReturnValue() {
            return isCascaded(annotationMetadata) || Arrays.stream(returnArgument.getTypeParameters()).anyMatch(ExecutableHierarchy::isCascaded);
        }

        boolean hasParameterGroupConversions() {
            return Arrays.stream(arguments).anyMatch(ExecutableHierarchy::hasGroupConversions);
        }

        boolean hasReturnValueGroupConversions() {
            return hasGroupConversions(annotationMetadata) || Arrays.stream(returnArgument.getTypeParameters()).anyMatch(ExecutableHierarchy::hasGroupConversions);
        }
    }

    /**
     * An executable with the declarations it inherits merged in.
     *
     * @param local              The executable as validated
     * @param declared           What its declaring type itself declares, the local one when unknown
     * @param inherited          The declarations it overrides or implements
     * @param annotationMetadata The merged executable annotations
     * @param arguments          The merged parameters
     * @param returnArgument     The merged return value
     */
    record Resolved(Declaration local,
                    Declaration declared,
                    List<Declaration> inherited,
                    AnnotationMetadata annotationMetadata,
                    Argument<?>[] arguments,
                    Argument<?> returnArgument) {

        /**
         * Parameter constraints, cascades and group conversions are declared once, at the root of the hierarchy.
         *
         * <p>When the declaring type is not introspected reflectively, the validated metadata may already
         * merge what the executable inherits: only what none of the inherited declarations carries counts
         * as added.</p>
         */
        void checkParameterDeclarations() {
            for (Argument<?> argument : declared.arguments()) {
                checkGroupConversions(argument);
            }
            if (inherited.isEmpty()) {
                return;
            }
            if (declared.exact() ? declared.hasParameterConstraintsOrCascades() : addsParameterConstraints()) {
                throw new ConstraintDeclarationException("Parameter constraints cannot be added in overriding or implementing methods: " + describe(declared));
            }
            if (parallel() && inherited.stream().anyMatch(Declaration::hasParameterConstraintsOrCascades)) {
                throw new ConstraintDeclarationException("Parallel method declarations cannot declare parameter constraints: " + describe(declared));
            }
            if (declared.exact() ? declared.hasParameterGroupConversions() : addsParameterGroupConversions()) {
                throw new ConstraintDeclarationException("Group conversions on parameters cannot be added in overriding or implementing methods: " + describe(declared));
            }
            if (parallel() && inherited.stream().anyMatch(Declaration::hasParameterGroupConversions)) {
                throw new ConstraintDeclarationException("Parallel method declarations cannot declare parameter group conversions: " + describe(declared));
            }
        }

        /**
         * A return value is marked cascaded once in the hierarchy, and its group conversions are not declared in parallel.
         */
        void checkReturnValueDeclarations() {
            checkGroupConversions(declared.annotationMetadata(), isCascaded(declared.annotationMetadata()));
            for (Argument<?> typeArgument : declared.returnArgument().getTypeParameters()) {
                checkGroupConversions(typeArgument);
            }
            if (inherited.isEmpty()) {
                return;
            }
            long inheritedCascaded = inherited.stream().filter(Declaration::hasCascadedReturnValue).count();
            if (declared.exact() && declared.hasCascadedReturnValue() && inheritedCascaded > 0
                || inheritedCascaded > 1 && hasCascadedReturnConflict(inherited)) {
                throw new ConstraintDeclarationException("Return value cannot be marked cascaded more than once in a method hierarchy: " + describe(declared));
            }
            if (parallel() && inherited.stream().anyMatch(Declaration::hasReturnValueGroupConversions)) {
                throw new ConstraintDeclarationException("Parallel method declarations cannot declare return value group conversions: " + describe(declared));
            }
        }

        private boolean addsParameterConstraints() {
            for (int i = 0; i < local.arguments().length; i++) {
                int index = i;
                Set<String> added = new HashSet<>(constraintNames(local.arguments()[i]));
                inherited.forEach(declaration -> added.removeAll(constraintNames(declaration.arguments()[index])));
                if (!added.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        private boolean addsParameterGroupConversions() {
            for (int i = 0; i < local.arguments().length; i++) {
                int index = i;
                Set<String> added = new HashSet<>(groupConversions(local.arguments()[i]));
                inherited.forEach(declaration -> added.removeAll(groupConversions(declaration.arguments()[index])));
                if (!added.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether the executable is declared in parallel branches of the hierarchy. Each declaration is read
         * from the introspection of the type declaring it, so a type that merely inherits the method does not
         * count as a declaration of its own.
         */
        private boolean parallel() {
            return inherited.size() > 1;
        }

        private static String describe(Declaration declaration) {
            return declaration.declaringType().getName();
        }
    }
}
