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

import io.micronaut.core.beans.BeanIntrospection;
import jakarta.validation.GroupDefinitionException;
import jakarta.validation.GroupSequence;
import jakarta.validation.groups.Default;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the steps requested groups expand to: group sequences, the default group sequence a bean redefines and
 * the groups converted below a cascade.
 *
 * @since 5.3.0
 */
final class GroupSequences {

    private static final Class<?>[] EMPTY_GROUPS = new Class<?>[0];

    private GroupSequences() {
    }

    static List<DefaultConstraintValidatorContext.ValidationGroup> findGroupSequences(FindGroupContext requested, BeanIntrospection<?> beanIntrospection) {
        // a cascade validates the bean in the groups it converted to: a group converted to the default group
        // expands to the default group sequence the bean redefines, as a requested default group does
        FindGroupContext ctx = requested;
        if (!requested.convertedGroups().isEmpty()) {
            List<Class<?>> converted = new ArrayList<>(requested.definedGroups().size());
            for (Class<?> group : requested.definedGroups()) {
                Class<?> convertedGroup = convertGroup(requested.convertedGroups(), group);
                if (!converted.contains(convertedGroup)) {
                    converted.add(convertedGroup);
                }
            }
            ctx = new FindGroupContext(requested.defaultValidator(), Map.of(), List.copyOf(converted), true);
        }
        // a requested group extending the default group validates its constraints itself, without the redefinition
        if (!ctx.definedGroups().contains(Default.class) || extendsDefaultGroup(ctx.definedGroups())) {
            return findGroupSequences(requested);
        }
        Class<Object>[] classGroupSequence = beanIntrospection.getAnnotationMetadata().classValues(GroupSequence.class);
        Class<?> defaultGroupType = beanIntrospection.getBeanType();
        if (classGroupSequence.length == 0) {
            return findGroupSequences(requested);
        }
        validateDefaultGroupSequence(defaultGroupType, classGroupSequence);
        // the other groups requested with the default group first: a failed step of the redefined sequence ends
        // the validation of the bean
        List<DefaultConstraintValidatorContext.ValidationGroup> dest = new ArrayList<>();
        List<Class<?>> others = new ArrayList<>(ctx.definedGroups().size());
        for (Class<?> group : ctx.definedGroups()) {
            if (group != Default.class) {
                others.add(group);
            }
        }
        if (!others.isEmpty()) {
            findGroups(ctx, dest, others, new HashSet<>());
        }
        dest.addAll(expandDefaultGroupSequence(ctx, defaultGroupType, classGroupSequence));
        return dest;
    }

    static boolean extendsDefaultGroup(List<Class<?>> groups) {
        for (Class<?> group : groups) {
            if (group != Default.class && Default.class.isAssignableFrom(group)) {
                return true;
            }
        }
        return false;
    }

    static void validateDefaultGroupSequence(Class<?> defaultGroupType,
                                                     Class<Object>[] classGroupSequence) {
        boolean containsDefaultGroupType = false;
        for (Class<?> group : classGroupSequence) {
            if (group == Default.class) {
                throw new GroupDefinitionException("Group sequence must not contain jakarta.validation.groups.Default");
            }
            containsDefaultGroupType |= group == defaultGroupType;
        }
        if (!containsDefaultGroupType) {
            throw new GroupDefinitionException("Group sequence is missing default group defined by the class of: " + defaultGroupType);
        }
    }

    static List<DefaultConstraintValidatorContext.ValidationGroup> expandDefaultGroupSequence(FindGroupContext ctx,
                                                                    Class<?> defaultGroupType,
                                                                    Class<Object>[] classGroupSequence) {
        List<DefaultConstraintValidatorContext.ValidationGroup> dest = new ArrayList<>();
        for (Class<Object> group : classGroupSequence) {
            if (group == defaultGroupType) {
                dest.add(new DefaultConstraintValidatorContext.ValidationGroup(true, true, List.of(Default.class), List.of(Default.class)));
            } else {
                int start = dest.size();
                findGroups(ctx, dest, List.of(group), new HashSet<>());
                // every group of the redefined default sequence is a step of it: a violation stops the sequence
                for (int i = start; i < dest.size(); i++) {
                    DefaultConstraintValidatorContext.ValidationGroup step = dest.get(i);
                    if (!step.isSequence()) {
                        dest.set(i, new DefaultConstraintValidatorContext.ValidationGroup(true, false, step.groups(), List.of(Default.class)));
                    } else {
                        dest.set(i, new DefaultConstraintValidatorContext.ValidationGroup(true, step.isRedefinedDefaultGroupSequence(), step.groups(), List.of(Default.class)));
                    }
                }
            }
        }
        return dest;
    }

    static List<DefaultConstraintValidatorContext.ValidationGroup> findGroupSequences(FindGroupContext ctx) {
        List<DefaultConstraintValidatorContext.ValidationGroup> dest = new ArrayList<>();
        findGroups(ctx, dest, ctx.definedGroups(), new HashSet<>());
        return dest;
    }

    static void findGroups(FindGroupContext ctx, List<DefaultConstraintValidatorContext.ValidationGroup> dest, Class<?> requestedGroup, Set<Class<?>> processedGroups) {
        Class<?> group = convertGroup(ctx.convertedGroups(), requestedGroup);
        if (!processedGroups.add(group)) {
            throw new GroupDefinitionException("Cyclical group: " + group);
        }
        // the groups being expanded, not every group seen: two sequences may share a group without a cycle
        try {
            expandGroup(ctx, dest, requestedGroup, group, processedGroups);
        } finally {
            processedGroups.remove(group);
        }
    }

    static void expandGroup(FindGroupContext ctx, List<DefaultConstraintValidatorContext.ValidationGroup> dest, Class<?> requestedGroup, Class<?> group, Set<Class<?>> processedGroups) {
        Class<?>[] groupSequence = ctx.defaultValidator().getBeanIntrospector().findIntrospection(group)
            .map(introspection -> (Class<?>[]) introspection.classValues(GroupSequence.class))
            .orElse(EMPTY_GROUPS);
        if (groupSequence.length == 0) {
            dest.add(new DefaultConstraintValidatorContext.ValidationGroup(false, false, ctx.exact() ? exactGroups(ctx, requestedGroup) : List.of(group), List.of(requestedGroup)));
            return;
        }
        int start = dest.size();
        for (Class<?> g : groupSequence) {
            if (g == Default.class) {
                throw new GroupDefinitionException("Group sequence must not contain jakarta.validation.groups.Default");
            }
            findGroups(ctx, dest, g, processedGroups);
        }
        // every group the sequence expanded to, wherever the destination started
        for (int i = start; i < dest.size(); i++) {
            DefaultConstraintValidatorContext.ValidationGroup vg = dest.get(i);
            dest.set(i, new DefaultConstraintValidatorContext.ValidationGroup(true, true, vg.groups(), vg.requested()));
        }
    }

    /**
     * The groups a group stands for below a conversion, listed one by one since they are matched exactly: the
     * group and each group it extends, every one converted on its own, a group converted to standing for itself
     * and the groups it extends. A group extending the default group keeps its own constraints when the
     * default group is converted, as in the reference implementation.
     */
    static List<Class<?>> exactGroups(FindGroupContext ctx, Class<?> group) {
        List<Class<?>> groups = new ArrayList<>();
        for (Class<?> inherited : inheritedGroups(ctx, group)) {
            Class<?> converted = convertGroup(ctx.convertedGroups(), inherited);
            if (converted == inherited) {
                if (!groups.contains(inherited)) {
                    groups.add(inherited);
                }
            } else {
                for (Class<?> target : inheritedGroups(ctx, converted)) {
                    if (!groups.contains(target)) {
                        groups.add(target);
                    }
                }
            }
        }
        return List.copyOf(groups);
    }

    /** The group and the groups it extends, transitively. */
    static List<Class<?>> inheritedGroups(FindGroupContext ctx, Class<?> group) {
        List<Class<?>> groups = new ArrayList<>();
        addInheritedGroups(ctx.defaultValidator().reflectionSupport(), group, groups);
        return groups;
    }

    static void addInheritedGroups(ReflectionSupport reflectionSupport, Class<?> group, List<Class<?>> groups) {
        if (!groups.contains(group)) {
            groups.add(group);
            if (reflectionSupport.canResolveHierarchy(group)) {
                for (Class<?> inherited : reflectionSupport.interfaces(group)) {
                    addInheritedGroups(reflectionSupport, inherited, groups);
                }
            }
        }
    }

    static Class<?> convertGroup(Map<Class<?>, Class<?>> convertedGroups, Class<?> group) {
        return convertedGroups.getOrDefault(group, group);
    }

    static void findGroups(FindGroupContext ctx, List<DefaultConstraintValidatorContext.ValidationGroup> dest, List<Class<?>> groupSequence, Set<Class<?>> processedGroups) {
        int start = dest.size();
        for (Class<?> g : groupSequence) {
            findGroups(ctx, dest, g, processedGroups);
        }
        boolean anySequence = false;
        for (int i = start; i < dest.size() && !anySequence; i++) {
            anySequence |= dest.get(i).isSequence();
        }
        if (!anySequence) {
            List<DefaultConstraintValidatorContext.ValidationGroup> subList = dest.subList(start, dest.size());
            List<Class<?>> copy = new ArrayList<>();
            List<Class<?>> requested = new ArrayList<>();
            for (DefaultConstraintValidatorContext.ValidationGroup validationGroup : subList) {
                copy.addAll(validationGroup.groups());
                requested.addAll(validationGroup.requested());
            }
            subList.clear();
            dest.add(new DefaultConstraintValidatorContext.ValidationGroup(false, false, copy, requested));
        }
    }

    /**
     * @param defaultValidator The validator
     * @param convertedGroups The conversions applying to the groups
     * @param definedGroups The groups requested
     * @param exact Whether the groups are validated below a conversion: each lists then the groups it extends
     */
    record FindGroupContext(
        DefaultValidator defaultValidator,
        Map<Class<?>, Class<?>> convertedGroups,
        List<Class<?>> definedGroups,
        boolean exact
    ) {
        FindGroupContext(DefaultValidator defaultValidator, Map<Class<?>, Class<?>> convertedGroups, List<Class<?>> definedGroups) {
            this(defaultValidator, convertedGroups, definedGroups, !convertedGroups.isEmpty());
        }

        boolean isDefault() {
            return definedGroups == DefaultConstraintValidatorContext.DEFAULT_GROUPS && convertedGroups.isEmpty();
        }
    }
}
