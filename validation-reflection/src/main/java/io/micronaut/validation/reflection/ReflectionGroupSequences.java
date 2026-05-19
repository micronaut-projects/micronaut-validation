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

import io.micronaut.core.annotation.Internal;
import io.micronaut.validation.validator.BeanValidationContext;
import jakarta.validation.GroupDefinitionException;
import jakarta.validation.GroupSequence;
import jakarta.validation.groups.Default;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reflection-only group sequence resolution.
 *
 * @since 5.1
 */
@Internal
final class ReflectionGroupSequences {

    private ReflectionGroupSequences() {
    }

    static List<List<Class<?>>> validationGroupPasses(Class<?> beanType, BeanValidationContext context) {
        List<Class<?>> groups = context.groups();
        if (groups.isEmpty()) {
            return defaultGroupPasses(beanType);
        }
        List<List<Class<?>>> passes = new ArrayList<>();
        List<Class<?>> regularGroups = new ArrayList<>();
        for (Class<?> group : groups) {
            GroupSequence groupSequence = group.getAnnotation(GroupSequence.class);
            if (groupSequence == null) {
                addInheritedGroups(group, regularGroups);
            } else {
                if (!regularGroups.isEmpty()) {
                    passes.add(List.copyOf(regularGroups));
                    regularGroups.clear();
                }
                addGroupSequencePasses(beanType, Default.class, passes, groupSequence.value(), new LinkedHashSet<>());
            }
        }
        if (!regularGroups.isEmpty()) {
            passes.add(List.copyOf(regularGroups));
        }
        return passes;
    }

    static boolean hasInheritedDefaultGroupSequence(Class<?> beanType, BeanValidationContext context) {
        List<Class<?>> groups = context.groups();
        if (!groups.isEmpty() && !(groups.size() == 1 && groups.contains(Default.class))) {
            return false;
        }
        if (beanType.getDeclaredAnnotation(GroupSequence.class) != null) {
            return false;
        }
        for (Class<?> current = beanType.getSuperclass(); current != null && current != Object.class; current = current.getSuperclass()) {
            if (current.getDeclaredAnnotation(GroupSequence.class) != null) {
                return true;
            }
        }
        return false;
    }

    static List<List<Class<?>>> inheritedDefaultGroupSequencePasses(Class<?> beanType) {
        for (Class<?> current = beanType.getSuperclass(); current != null && current != Object.class; current = current.getSuperclass()) {
            GroupSequence groupSequence = current.getDeclaredAnnotation(GroupSequence.class);
            if (groupSequence != null) {
                return defaultGroupPasses(current, groupSequence.value(), current);
            }
        }
        return List.of();
    }

    private static List<List<Class<?>>> defaultGroupPasses(Class<?> beanType) {
        GroupSequence groupSequence = beanType.getDeclaredAnnotation(GroupSequence.class);
        if (groupSequence != null) {
            return defaultGroupPasses(beanType, groupSequence.value(), Default.class);
        }
        for (Class<?> current = beanType.getSuperclass(); current != null && current != Object.class; current = current.getSuperclass()) {
            groupSequence = current.getDeclaredAnnotation(GroupSequence.class);
            if (groupSequence != null) {
                List<List<Class<?>>> passes = defaultGroupPasses(current, groupSequence.value(), current);
                List<Class<?>> firstPass = new ArrayList<>(passes.get(0));
                firstPass.add(0, beanType);
                passes.set(0, List.copyOf(firstPass));
                return passes;
            }
        }
        return List.of(List.of(Default.class));
    }

    private static List<List<Class<?>>> defaultGroupPasses(Class<?> sequenceOwner,
                                                           Class<?>[] sequence,
                                                           Class<?> defaultGroupReplacement) {
        if (Arrays.asList(sequence).contains(Default.class)) {
            throw new GroupDefinitionException("Group sequence for " + sequenceOwner.getName() + " must not contain jakarta.validation.groups.Default");
        }
        if (!Arrays.asList(sequence).contains(sequenceOwner)) {
            throw new GroupDefinitionException("Group sequence for " + sequenceOwner.getName() + " must contain the class itself");
        }
        List<List<Class<?>>> passes = new ArrayList<>();
        addGroupSequencePasses(sequenceOwner, defaultGroupReplacement, passes, sequence, new LinkedHashSet<>());
        return passes;
    }

    private static void addGroupSequencePasses(Class<?> beanType,
                                               Class<?> defaultGroupReplacement,
                                               List<List<Class<?>>> passes,
                                               Class<?>[] sequence,
                                               Set<Class<?>> processedGroups) {
        for (Class<?> group : sequence) {
            if (group == Default.class) {
                throw new GroupDefinitionException("Group sequence must not contain jakarta.validation.groups.Default");
            }
            if (group == beanType) {
                passes.add(List.of(defaultGroupReplacement));
                continue;
            }
            if (!processedGroups.add(group)) {
                throw new GroupDefinitionException("Cyclical group sequence: " + group.getName());
            }
            GroupSequence nestedSequence = group.getAnnotation(GroupSequence.class);
            if (nestedSequence == null) {
                List<Class<?>> groups = new ArrayList<>();
                addInheritedGroups(group, groups);
                passes.add(List.copyOf(groups));
            } else {
                addGroupSequencePasses(beanType, defaultGroupReplacement, passes, nestedSequence.value(), processedGroups);
            }
        }
    }

    private static void addInheritedGroups(Class<?> group, List<Class<?>> groups) {
        if (!groups.contains(group)) {
            groups.add(group);
        }
        for (Class<?> inheritedGroup : group.getInterfaces()) {
            addInheritedGroups(inheritedGroup, groups);
        }
    }
}
