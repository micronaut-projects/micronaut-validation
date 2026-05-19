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
                addGroupSequencePasses(beanType, passes, groupSequence.value(), new LinkedHashSet<>());
            }
        }
        if (!regularGroups.isEmpty()) {
            passes.add(List.copyOf(regularGroups));
        }
        return passes;
    }

    private static List<List<Class<?>>> defaultGroupPasses(Class<?> beanType) {
        GroupSequence groupSequence = beanType.getAnnotation(GroupSequence.class);
        if (groupSequence == null) {
            return List.of(List.of(Default.class));
        }
        Class<?>[] sequence = groupSequence.value();
        if (Arrays.asList(sequence).contains(Default.class)) {
            throw new GroupDefinitionException("Group sequence for " + beanType.getName() + " must not contain jakarta.validation.groups.Default");
        }
        if (!Arrays.asList(sequence).contains(beanType)) {
            throw new GroupDefinitionException("Group sequence for " + beanType.getName() + " must contain the class itself");
        }
        List<List<Class<?>>> passes = new ArrayList<>();
        addGroupSequencePasses(beanType, passes, sequence, new LinkedHashSet<>());
        return passes;
    }

    private static void addGroupSequencePasses(Class<?> beanType,
                                               List<List<Class<?>>> passes,
                                               Class<?>[] sequence,
                                               Set<Class<?>> processedGroups) {
        for (Class<?> group : sequence) {
            if (group == Default.class) {
                throw new GroupDefinitionException("Group sequence must not contain jakarta.validation.groups.Default");
            }
            if (group == beanType) {
                passes.add(List.of(Default.class));
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
                addGroupSequencePasses(beanType, passes, nestedSequence.value(), processedGroups);
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
