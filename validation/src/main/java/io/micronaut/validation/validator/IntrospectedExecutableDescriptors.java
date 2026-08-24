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
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanConstructor;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.type.Argument;
import io.micronaut.validation.validator.constraints.ConstraintValidatorTargetResolver;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.Valid;
import jakarta.validation.constraintvalidation.ValidationTarget;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ConstructorDescriptor;
import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.CrossParameterDescriptor;
import jakarta.validation.metadata.ElementDescriptor;
import jakarta.validation.metadata.GroupConversionDescriptor;
import jakarta.validation.metadata.MethodDescriptor;
import jakarta.validation.metadata.ParameterDescriptor;
import jakarta.validation.metadata.ReturnValueDescriptor;
import jakarta.validation.metadata.Scope;

import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The descriptors of the executables, the parameters, the return values and the container elements of a
 * bean, built from the metadata of its {@link io.micronaut.core.beans.BeanIntrospection} — generated at
 * compilation time or reflective — rather than from reflection of their own.
 *
 * <p>A constraint declared on a method or a constructor belongs to its return value, to its parameters as a
 * cross-parameter constraint, or to both, as the section 4.5.2.1 of the specification decides:
 * {@code validationAppliesTo} when the constraint declares it, else the validation targets its validators
 * support. The rule is the one {@code DefaultValidator} applies when it validates, so that the metadata and
 * the validation agree.</p>
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
final class IntrospectedExecutableDescriptors {

    private final Function<AnnotationMetadata, Set<ConstraintDescriptor<?>>> constraints;
    @Nullable
    private final ValidatorDeclarations declarations;

    /**
     * @param constraints The factory of the constraint descriptors of an annotation metadata
     */
    IntrospectedExecutableDescriptors(Function<AnnotationMetadata, Set<ConstraintDescriptor<?>>> constraints) {
        this(constraints, null);
    }

    IntrospectedExecutableDescriptors(Function<AnnotationMetadata, Set<ConstraintDescriptor<?>>> constraints, @Nullable ValidatorDeclarations declarations) {
        this.constraints = constraints;
        this.declarations = declarations;
    }

    /**
     * @param method The method
     * @return The descriptor of the method, constrained or not
     */
    MethodDescriptor method(BeanMethod<?, ?> method) {
        return new IntrospectedMethodDescriptor(method);
    }

    /**
     * @param constructor The constructor
     * @return The descriptor of the constructor, constrained or not
     */
    ConstructorDescriptor constructor(BeanConstructor<?> constructor) {
        return new IntrospectedConstructorDescriptor(constructor);
    }

    /**
     * @param descriptor The descriptor of an executable
     * @return Whether the executable has a constrained parameter, cross-parameter constraints or a
     * constrained return value
     */
    static boolean isConstrained(jakarta.validation.metadata.ExecutableDescriptor descriptor) {
        return descriptor.hasConstrainedParameters() || descriptor.hasConstrainedReturnValue();
    }

    /**
     * The container element descriptors of an argument: one per type argument that is constrained, cascaded,
     * converts groups or holds constrained container elements of its own.
     *
     * @param argument The argument
     * @return The descriptors
     */
    Set<ContainerElementTypeDescriptor> containerElements(Argument<?> argument) {
        Argument<?>[] typeParameters = argument.getTypeParameters();
        if (typeParameters.length == 0) {
            return Collections.emptySet();
        }
        Set<ContainerElementTypeDescriptor> descriptors = new LinkedHashSet<>();
        for (int i = 0; i < typeParameters.length; i++) {
            IntrospectedContainerElementDescriptor descriptor = new IntrospectedContainerElementDescriptor(argument.getType(), i, typeParameters[i]);
            if (descriptor.hasConstraints() || descriptor.isCascaded() || !descriptor.getGroupConversions().isEmpty()
                || !descriptor.getConstrainedContainerElementTypes().isEmpty()) {
                descriptors.add(descriptor);
            }
        }
        return descriptors;
    }

    /**
     * The group conversions declared by an element.
     *
     * @param annotationMetadata The metadata of the element
     * @return The conversions
     */
    static Set<GroupConversionDescriptor> groupConversions(AnnotationMetadata annotationMetadata) {
        List<AnnotationValue<ConvertGroup>> conversions = annotationMetadata.getAnnotationValuesByType(ConvertGroup.class);
        if (conversions.isEmpty()) {
            return Collections.emptySet();
        }
        Map<Class<?>, Class<?>> groups = new LinkedHashMap<>();
        for (AnnotationValue<ConvertGroup> conversion : conversions) {
            Class<?> from = conversion.classValue("from").orElse(Default.class);
            Class<?> to = conversion.classValue("to")
                .orElseThrow(() -> new ConstraintDeclarationException("Group conversion is missing a target group"));
            Class<?> previous = groups.putIfAbsent(from, to);
            if (previous != null) {
                throw new ConstraintDeclarationException("Multiple group conversions declare the same source group: " + from.getName());
            }
        }
        Set<GroupConversionDescriptor> descriptors = new LinkedHashSet<>();
        groups.forEach((from, to) -> descriptors.add(new DefaultGroupConversionDescriptor(from, to)));
        return descriptors;
    }

    /**
     * Whether a constraint declared on an executable applies to the given target.
     */
    private static boolean targets(ConstraintDescriptor<?> descriptor, ConstraintTarget target) {
        ConstraintTarget validationAppliesTo = descriptor.getValidationAppliesTo();
        if (validationAppliesTo != null && validationAppliesTo != ConstraintTarget.IMPLICIT) {
            return validationAppliesTo == target;
        }
        Set<ValidationTarget> supported = new LinkedHashSet<>();
        for (Class<?> validatorClass : descriptor.getConstraintValidatorClasses()) {
            Set<ValidationTarget> validatorTargets = ConstraintValidatorTargetResolver.validationTargets(validatorClass);
            if (validatorTargets.isEmpty()) {
                supported.add(ValidationTarget.ANNOTATED_ELEMENT);
            } else {
                supported.addAll(validatorTargets);
            }
        }
        if (supported.isEmpty()) {
            supported.add(ValidationTarget.ANNOTATED_ELEMENT);
        }
        return target == ConstraintTarget.PARAMETERS
            ? supported.contains(ValidationTarget.PARAMETERS)
            : supported.contains(ValidationTarget.ANNOTATED_ELEMENT);
    }

    static boolean matchesGroups(ConstraintDescriptor<?> descriptor, Set<Class<?>> requested) {
        if (requested.isEmpty()) {
            return true;
        }
        for (Class<?> requestedGroup : requested) {
            for (Class<?> group : descriptor.getGroups()) {
                if (group.isAssignableFrom(requestedGroup)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The part shared by the elements: a metadata, the constraints it declares filtered by the groups of the
     * finder, the cascade and the group conversions.
     */
    private abstract class IntrospectedElement implements ElementDescriptor, ElementDescriptor.ConstraintFinder {

        final AnnotationMetadata annotationMetadata;
        final Set<Class<?>> groups;

        IntrospectedElement(AnnotationMetadata annotationMetadata, Set<Class<?>> groups) {
            this.annotationMetadata = annotationMetadata;
            this.groups = groups;
        }

        /**
         * @return The constraints of the element before the group filter
         */
        Set<ConstraintDescriptor<?>> allConstraints() {
            return constraints.apply(annotationMetadata);
        }

        @Override
        public boolean hasConstraints() {
            return !getConstraintDescriptors().isEmpty();
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            Set<ConstraintDescriptor<?>> filtered = new LinkedHashSet<>();
            for (ConstraintDescriptor<?> descriptor : allConstraints()) {
                if (matchesGroups(descriptor, groups)) {
                    filtered.add(descriptor);
                }
            }
            return filtered;
        }

        @Override
        public ConstraintFinder findConstraints() {
            return withGroups(Set.of());
        }

        @Override
        public ConstraintFinder unorderedAndMatchingGroups(Class<?>... requested) {
            return withGroups(new LinkedHashSet<>(List.of(requested)));
        }

        @Override
        public ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }

        abstract ConstraintFinder withGroups(Set<Class<?>> groups);

        boolean cascaded() {
            return annotationMetadata.hasAnnotation(Valid.class);
        }
    }

    /**
     * A parameter of an executable.
     */
    private final class IntrospectedParameterDescriptor extends IntrospectedElement implements ParameterDescriptor {

        private final int index;
        private final Argument<?> argument;

        IntrospectedParameterDescriptor(int index, Argument<?> argument) {
            this(index, argument, Set.of());
        }

        private IntrospectedParameterDescriptor(int index, Argument<?> argument, Set<Class<?>> groups) {
            super(argument.getAnnotationMetadata(), groups);
            this.index = index;
            this.argument = argument;
        }

        @Override
        public int getIndex() {
            return index;
        }

        @Override
        public String getName() {
            return argument.getName();
        }

        @Override
        public boolean isCascaded() {
            return cascaded();
        }

        @Override
        public Set<GroupConversionDescriptor> getGroupConversions() {
            return groupConversions(annotationMetadata);
        }

        @Override
        public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
            return containerElements(argument);
        }

        @Override
        public Class<?> getElementClass() {
            return argument.getType();
        }

        @Override
        ConstraintFinder withGroups(Set<Class<?>> groups) {
            return new IntrospectedParameterDescriptor(index, argument, groups);
        }

        boolean isConstrained() {
            return !allConstraints().isEmpty() || isCascaded() || !getConstrainedContainerElementTypes().isEmpty();
        }
    }

    /**
     * The return value of an executable: the constraints of the executable that target it, the cascade
     * declared on the executable or on its return type, and the container elements of the return type.
     */
    private final class IntrospectedReturnValueDescriptor extends IntrospectedElement implements ReturnValueDescriptor {

        private final Argument<?> returnArgument;

        IntrospectedReturnValueDescriptor(AnnotationMetadata executableMetadata, Argument<?> returnArgument) {
            this(executableMetadata, returnArgument, Set.of());
        }

        private IntrospectedReturnValueDescriptor(AnnotationMetadata executableMetadata, Argument<?> returnArgument, Set<Class<?>> groups) {
            super(executableMetadata, groups);
            this.returnArgument = returnArgument;
        }

        @Override
        Set<ConstraintDescriptor<?>> allConstraints() {
            Set<ConstraintDescriptor<?>> targeted = new LinkedHashSet<>();
            for (ConstraintDescriptor<?> descriptor : constraints.apply(annotationMetadata)) {
                if (targets(descriptor, ConstraintTarget.RETURN_VALUE)) {
                    targeted.add(descriptor);
                }
            }
            return targeted;
        }

        @Override
        public boolean isCascaded() {
            return cascaded() || returnArgument.getAnnotationMetadata().hasAnnotation(Valid.class);
        }

        @Override
        public Set<GroupConversionDescriptor> getGroupConversions() {
            Set<GroupConversionDescriptor> conversions = new LinkedHashSet<>(groupConversions(annotationMetadata));
            conversions.addAll(groupConversions(returnArgument.getAnnotationMetadata()));
            return conversions;
        }

        @Override
        public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
            return containerElements(returnArgument);
        }

        @Override
        public Class<?> getElementClass() {
            return returnArgument.getType();
        }

        @Override
        ConstraintFinder withGroups(Set<Class<?>> groups) {
            return new IntrospectedReturnValueDescriptor(annotationMetadata, returnArgument, groups);
        }

        boolean isConstrained() {
            return !allConstraints().isEmpty() || isCascaded() || !getConstrainedContainerElementTypes().isEmpty();
        }
    }

    /**
     * The cross-parameter constraints of an executable: the constraints of the executable that target the
     * parameters.
     */
    private final class IntrospectedCrossParameterDescriptor extends IntrospectedElement implements CrossParameterDescriptor {

        private final AnnotationMetadata declaredMetadata;
        private final Scope scope;

        IntrospectedCrossParameterDescriptor(AnnotationMetadata executableMetadata, AnnotationMetadata declaredMetadata) {
            this(executableMetadata, declaredMetadata, Set.of(), Scope.HIERARCHY);
        }

        private IntrospectedCrossParameterDescriptor(AnnotationMetadata executableMetadata, AnnotationMetadata declaredMetadata, Set<Class<?>> groups, Scope scope) {
            super(executableMetadata, groups);
            this.declaredMetadata = declaredMetadata;
            this.scope = scope;
        }

        /**
         * The executable's constraints targeting the parameters. The metadata of an executable is the one of
         * its declaration, so an executable inherited from a super type has no local constraints.
         */
        @Override
        Set<ConstraintDescriptor<?>> allConstraints() {
            Set<ConstraintDescriptor<?>> targeted = new LinkedHashSet<>();
            for (ConstraintDescriptor<?> descriptor : constraints.apply(scope == Scope.LOCAL_ELEMENT ? declaredMetadata : annotationMetadata)) {
                if (targets(descriptor, ConstraintTarget.PARAMETERS)) {
                    targeted.add(descriptor);
                }
            }
            return targeted;
        }

        @Override
        public Class<?> getElementClass() {
            return Object[].class;
        }

        @Override
        public ConstraintFinder lookingAt(Scope requested) {
            return new IntrospectedCrossParameterDescriptor(annotationMetadata, declaredMetadata, groups, requested);
        }

        @Override
        ConstraintFinder withGroups(Set<Class<?>> groups) {
            return new IntrospectedCrossParameterDescriptor(annotationMetadata, declaredMetadata, groups, scope);
        }
    }

    /**
     * A constrained type argument of a container.
     */
    private final class IntrospectedContainerElementDescriptor extends IntrospectedElement implements ContainerElementTypeDescriptor {

        private final Class<?> containerClass;
        private final int typeArgumentIndex;
        private final Argument<?> typeArgument;

        IntrospectedContainerElementDescriptor(Class<?> containerClass, int typeArgumentIndex, Argument<?> typeArgument) {
            this(containerClass, typeArgumentIndex, typeArgument, Set.of());
        }

        private IntrospectedContainerElementDescriptor(Class<?> containerClass, int typeArgumentIndex, Argument<?> typeArgument, Set<Class<?>> groups) {
            super(typeArgument.getAnnotationMetadata(), groups);
            this.containerClass = containerClass;
            this.typeArgumentIndex = typeArgumentIndex;
            this.typeArgument = typeArgument;
        }

        @Override
        public Class<?> getContainerClass() {
            return containerClass;
        }

        @Override
        public Integer getTypeArgumentIndex() {
            return typeArgumentIndex;
        }

        @Override
        public boolean isCascaded() {
            return cascaded();
        }

        @Override
        public Set<GroupConversionDescriptor> getGroupConversions() {
            return groupConversions(annotationMetadata);
        }

        @Override
        public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
            return containerElements(typeArgument);
        }

        @Override
        public Class<?> getElementClass() {
            return typeArgument.getType();
        }

        @Override
        ConstraintFinder withGroups(Set<Class<?>> groups) {
            return new IntrospectedContainerElementDescriptor(containerClass, typeArgumentIndex, typeArgument, groups);
        }
    }

    /**
     * The part shared by the methods and the constructors.
     */
    private abstract class IntrospectedExecutableDescriptor implements jakarta.validation.metadata.ExecutableDescriptor, ElementDescriptor.ConstraintFinder {

        private final String name;
        private final AnnotationMetadata annotationMetadata;
        private final List<ParameterDescriptor> parameters;
        private final IntrospectedCrossParameterDescriptor crossParameters;
        private final IntrospectedReturnValueDescriptor returnValue;

        IntrospectedExecutableDescriptor(String name, AnnotationMetadata annotationMetadata, Argument<?>[] arguments, Argument<?> returnArgument, AnnotationMetadata declaredMetadata) {
            this.name = name;
            this.annotationMetadata = annotationMetadata;
            List<ParameterDescriptor> descriptors = new ArrayList<>(arguments.length);
            for (int i = 0; i < arguments.length; i++) {
                descriptors.add(new IntrospectedParameterDescriptor(i, arguments[i]));
            }
            this.parameters = Collections.unmodifiableList(descriptors);
            this.crossParameters = new IntrospectedCrossParameterDescriptor(annotationMetadata, declaredMetadata);
            this.returnValue = new IntrospectedReturnValueDescriptor(annotationMetadata, returnArgument);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public List<ParameterDescriptor> getParameterDescriptors() {
            return parameters;
        }

        @Override
        public CrossParameterDescriptor getCrossParameterDescriptor() {
            return crossParameters;
        }

        @Override
        public ReturnValueDescriptor getReturnValueDescriptor() {
            return returnValue;
        }

        @Override
        public boolean hasConstrainedParameters() {
            if (crossParameters.hasConstraints()) {
                return true;
            }
            for (ParameterDescriptor parameter : parameters) {
                if (((IntrospectedParameterDescriptor) parameter).isConstrained()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean hasConstrainedReturnValue() {
            return returnValue.isConstrained();
        }

        @Override
        public boolean hasConstraints() {
            return false;
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            return Collections.emptySet();
        }

        @Override
        public ConstraintFinder findConstraints() {
            return this;
        }

        @Override
        public ConstraintFinder unorderedAndMatchingGroups(Class<?>... groups) {
            return this;
        }

        @Override
        public ConstraintFinder lookingAt(Scope scope) {
            return this;
        }

        @Override
        public ConstraintFinder declaredOn(ElementType... types) {
            return this;
        }

        AnnotationMetadata annotationMetadata() {
            return annotationMetadata;
        }
    }

    private final class IntrospectedMethodDescriptor extends IntrospectedExecutableDescriptor implements MethodDescriptor {

        private final Class<?> returnType;

        IntrospectedMethodDescriptor(BeanMethod<?, ?> method) {
            this(method, declarations == null ? null : declarations.resolveHierarchy(method));
        }

        private IntrospectedMethodDescriptor(BeanMethod<?, ?> method, @Nullable ExecutableHierarchy.Resolved hierarchy) {
            super(method.getName(),
                hierarchy == null ? method.getAnnotationMetadata() : hierarchy.annotationMetadata(),
                hierarchy == null ? method.getArguments() : hierarchy.arguments(),
                hierarchy == null ? method.getReturnType().asArgument() : hierarchy.returnArgument(),
                declaredMetadata(method, hierarchy));
            this.returnType = method.getReturnType().getType();
        }

        /**
         * What the method declares itself, for the local scope: the exact declaration when the hierarchy knows
         * it, the whole metadata of a method the bean type declares, nothing for an inherited method.
         */
        private static AnnotationMetadata declaredMetadata(BeanMethod<?, ?> method, @Nullable ExecutableHierarchy.Resolved hierarchy) {
            if (method.getDeclaringType() != method.getDeclaringBean().getBeanType()) {
                // inherited as is: the described type declares nothing on it
                return AnnotationMetadata.EMPTY_METADATA;
            }
            if (hierarchy != null && hierarchy.declared().exact()) {
                return hierarchy.declared().annotationMetadata();
            }
            return method.getAnnotationMetadata();
        }

        @Override
        public Class<?> getElementClass() {
            return returnType;
        }
    }

    private final class IntrospectedConstructorDescriptor extends IntrospectedExecutableDescriptor implements ConstructorDescriptor {

        private final Class<?> beanType;

        IntrospectedConstructorDescriptor(BeanConstructor<?> constructor) {
            super(constructor.getDeclaringBeanType().getSimpleName(),
                constructor.getAnnotationMetadata(),
                constructor.getArguments(),
                Argument.of(constructor.getDeclaringBeanType()),
                constructor.getAnnotationMetadata());
            this.beanType = constructor.getDeclaringBeanType();
        }

        @Override
        public Class<?> getElementClass() {
            return beanType;
        }
    }

    /**
     * A group conversion.
     *
     * @param from The source group
     * @param to   The target group
     */
    record DefaultGroupConversionDescriptor(Class<?> from, Class<?> to) implements GroupConversionDescriptor {

        @Override
        public Class<?> getFrom() {
            return from;
        }

        @Override
        public Class<?> getTo() {
            return to;
        }
    }

}
