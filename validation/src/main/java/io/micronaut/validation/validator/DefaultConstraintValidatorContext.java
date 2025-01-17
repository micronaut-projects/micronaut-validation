/*
 * Copyright 2017-2023 original authors
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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.GroupDefinitionException;
import jakarta.validation.GroupSequence;
import jakarta.validation.ValidationException;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.ConstraintDescriptor;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The implementation of {@link ConstraintValidatorContext}.
 *
 * @param <R> The root bean type
 */
@Internal
public final class DefaultConstraintValidatorContext<R> implements ConstraintValidatorContext {

    private static final Map<Class<?>, List<Class<?>>> GROUP_SEQUENCES = new ConcurrentHashMap<>();
    private static final List<Class<?>> DEFAULT_GROUPS = Collections.singletonList(Default.class);

    boolean disableDefaultConstraintViolation;
    ConstraintDescriptor<Annotation> constraint;

    private final BeanValidationContext validationContext;
    private final DefaultValidator defaultValidator;
    private final BeanIntrospection<R> beanIntrospection;
    private final R rootBean;
    @Nullable
    private final Class<R> rootClass;
    private final Set<Object> validatedObjects = new HashSet<>(20);
    private final ValidationPath currentPath;
    private final List<Class<?>> definedGroups;
    private String messageTemplate = null;
    private final Set<ConstraintViolation<R>> overallViolations;

    // Contextual values
    @Nullable
    private Object[] executableParameterValues;
    @Nullable
    private Object executableReturnValue;
    private List<Class<?>> currentGroups;
    private Map<Class<?>, Class<?>> convertedGroups = Collections.emptyMap();
    private Set<ConstraintViolation<R>> currentViolations = new LinkedHashSet<>();

    DefaultConstraintValidatorContext(DefaultValidator defaultValidator, BeanIntrospection<R> beanIntrospection, R rootBean, BeanValidationContext validationContext) {
        this(defaultValidator, beanIntrospection, validationContext, rootBean, null, new ValidationPath(), new LinkedHashSet<>(), null, Collections.emptyList());
    }

    private DefaultConstraintValidatorContext(DefaultValidator defaultValidator,
                                              BeanIntrospection<R> beanIntrospection,
                                              BeanValidationContext validationContext, R rootBean,
                                              Object executableReturnValue,
                                              ValidationPath path,
                                              Set<ConstraintViolation<R>> overallViolations,
                                              Object[] executableParameterValues,
                                              List<Class<?>> currentGroups) {
        this.validationContext = validationContext;
        this.defaultValidator = defaultValidator;
        this.beanIntrospection = beanIntrospection;
        this.rootBean = rootBean;
        this.rootClass = beanIntrospection == null ? (rootBean == null ? null : (Class<R>) rootBean.getClass()) : beanIntrospection.getBeanType();
        this.executableParameterValues = executableParameterValues;
        this.executableReturnValue = executableReturnValue;
        this.definedGroups = processGroups(validationContext.groups());
        this.currentGroups = currentGroups;
        this.currentPath = path != null ? path : new ValidationPath();
        this.overallViolations = overallViolations;
    }

    /**
     * The validation context.
     * @return The context
     */
    public @NonNull BeanValidationContext getValidationContext() {
        return validationContext;
    }

    private static List<Class<?>> processGroups(List<Class<?>> definedGroups) {
        if (CollectionUtils.isEmpty(definedGroups)) {
            return DEFAULT_GROUPS;
        }
        sanityCheckGroups(definedGroups);
        List<Class<?>> groupList = new ArrayList<>();
        for (Class<?> group : definedGroups) {
            addInheritedGroups(group, groupList);
        }
        return Collections.unmodifiableList(groupList);
    }

    private static void sanityCheckGroups(List<Class<?>> groups) {
        ArgumentUtils.requireNonNull("groups", groups);

        for (Class<?> clazz : groups) {
            if (clazz == null) {
                throw new IllegalArgumentException("Validation groups must be non-null");
            }
            if (!clazz.isInterface()) {
                throw new IllegalArgumentException(
                    "Validation groups must be interfaces. " + clazz.getName() + " is not.");
            }
        }
    }

    public boolean hasDefaultGroup() {
        return definedGroups.equals(DEFAULT_GROUPS);
    }

    public boolean containsGroup(Collection<Class<?>> constraintGroups) {
        if (currentGroups.contains(Default.class) && rootClass != null && constraintGroups.contains(rootClass)) {
            return true;
        }
        return currentGroups.stream().anyMatch(constraintGroups::contains);
    }

    public Object[] getExecutableParameterValues() {
        return executableParameterValues;
    }

    public Object getExecutableReturnValue() {
        return executableReturnValue;
    }

    public boolean isValidated(Object obj) {
        return validatedObjects.contains(obj);
    }

    public ValidationCloseable validating(Object obj) {
        validatedObjects.add(obj);
        return () -> validatedObjects.remove(obj);
    }

    public ValidationCloseable withExecutableParameterValues(Object[] executableParameterValues) {
        Object[] prevExecutableParameterValues = this.executableParameterValues;
        this.executableParameterValues = executableParameterValues;
        return () -> this.executableParameterValues = prevExecutableParameterValues;
    }

    public ValidationCloseable withExecutableReturnValue(Object executableReturnValue) {
        Object prevExecutableReturnValue = this.executableReturnValue;
        this.executableReturnValue = executableReturnValue;
        return () -> this.executableReturnValue = prevExecutableReturnValue;
    }

    public GroupsValidation withGroupSequence(@NonNull ValidationGroup validationGroup) {
        List<Class<?>> prevGroups = currentGroups;
        Set<ConstraintViolation<R>> prevViolations = currentViolations;
        currentGroups = validationGroup.groups();
        currentViolations = new LinkedHashSet<>();

        return new GroupsValidation() {

            @Override
            public boolean isFailed() {
                // According to the TCK, the validation with a group sequence
                // should fail immediately if group or a cascaded element has violations,
                // but redefined default groups should continue to the other group validation
                if (validationGroup.isRedefinedDefaultGroupSequence()) {
                    return !overallViolations.isEmpty();
                }
                return !currentViolations.isEmpty();
            }

            @Override
            public void close() {
                currentGroups = prevGroups;
                currentViolations = prevViolations;
            }
        };
    }

    public ValidationCloseable convertGroups(@NonNull AnnotationMetadata annotationMetadata) {
        List<AnnotationValue<ConvertGroup>> conversions = annotationMetadata.getAnnotationValuesByType(ConvertGroup.class);
        if (conversions.isEmpty()) {
            return () -> {
            };
        }
        Map<Class<?>, Class<?>> prevConvertedGroups = convertedGroups;
        List<Class<?>> prevGroups = currentGroups;
        convertedGroups = new HashMap<>(prevConvertedGroups);

        Map<Class<?>, Class<?>> newConvertGroups = conversions.stream().collect(Collectors.toMap(
            av -> av.classValue("from").orElse(Default.class),
            av -> av.classValue("to").orElseThrow())
        );
        convertedGroups.putAll(newConvertGroups);
        currentGroups = prevGroups.stream().<Class<?>>map(this::convertGroup).toList();
        return () -> {
            convertedGroups = prevConvertedGroups;
            currentGroups = prevGroups;
        };
    }

    public List<ValidationGroup> findGroupSequences(BeanIntrospection<?> beanIntrospection) {
        if (hasDefaultGroup()) {
            Class<Object>[] classGroupSequence = beanIntrospection.classValues(GroupSequence.class);
            if (classGroupSequence.length > 0) {
                if (Arrays.stream(classGroupSequence).noneMatch(c -> c == beanIntrospection.getBeanType())) {
                    throw new GroupDefinitionException("Group sequence is missing default group defined by the class of: " + beanIntrospection.getBeanType());
                }
                return Arrays.stream(classGroupSequence)
                    .flatMap(group -> {
                        if (group == beanIntrospection.getBeanType()) {
                            return Stream.of(new ValidationGroup(true, true, List.of(Default.class)));
                        }
                        return findGroupSequence(Collections.singletonList(group), new HashSet<>()).stream();
                    })
                    .toList();
            }
        }
        return findGroupSequence(definedGroups, new HashSet<>());
    }

    public List<ValidationGroup> findGroupSequences() {
        return findGroupSequence(definedGroups, new HashSet<>());
    }

    private List<ValidationGroup> findGroupSequence(List<Class<?>> groups, Set<Class<?>> processedGroups) {
        return findGroups(groups, processedGroups).stream().toList();
    }

    private List<ValidationGroup> findGroups(Class<?> group, Set<Class<?>> processedGroups) {
        if (convertedGroups != null) {
            group = convertGroup(group);
        }
        if (!processedGroups.add(group)) {
            throw new GroupDefinitionException("Cyclical group: " + group);
        }
        Class<?> finalGroup = group;
        List<Class<?>> groupSequence = GROUP_SEQUENCES.computeIfAbsent(group, ignore -> {
            return defaultValidator.getBeanIntrospector().findIntrospection(finalGroup).stream()
                .<Class<?>>flatMap(introspection -> Arrays.stream(introspection.classValues(GroupSequence.class)))
                .toList();
        });
        if (groupSequence.isEmpty()) {
            return List.of(new ValidationGroup(false, false, List.of(group)));
        }
        return groupSequence.stream()
            .flatMap(g -> findGroups(g, processedGroups).stream().map(vg -> new ValidationGroup(true, true, vg.groups))).toList();
    }

    private Class<?> convertGroup(Class<?> group) {
        Class<?> newGroup = convertedGroups.get(group);
        if (newGroup == null) {
            return group;
        }
        return newGroup;
    }

    private List<ValidationGroup> findGroups(List<Class<?>> groupSequence, Set<Class<?>> processedGroups) {
        List<ValidationGroup> innerGroups = groupSequence.stream().flatMap(g -> findGroups(g, processedGroups).stream()).toList();
        if (innerGroups.stream().noneMatch(validationGroup -> validationGroup.isSequence)) {
            return List.of(
                new ValidationGroup(
                    false,
                    false,
                    innerGroups.stream().flatMap(validationGroup -> validationGroup.groups.stream()).toList()
                )
            );
        }
        return innerGroups;
    }

    public void addViolation(DefaultConstraintViolation<R> violation) {
        if (currentViolations != null) {
            currentViolations.add(violation);
        }
        overallViolations.add(violation);
    }

    public Set<ConstraintViolation<R>> getOverallViolations() {
        return overallViolations;
    }

    public ValidationPath getCurrentPath() {
        return currentPath;
    }

    @Nullable
    @Override
    public R getRootBean() {
        return rootBean;
    }

    public Class<R> getRootClass() {
        return rootClass;
    }

    private static void addInheritedGroups(Class<?> group, List<Class<?>> groups) {
        if (!groups.contains(group)) {
            groups.add(group);
        }

        for (Class<?> inheritedGroup : group.getInterfaces()) {
            addInheritedGroups(inheritedGroup, groups);
        }
    }

    @Override
    public void disableDefaultConstraintViolation() {
        disableDefaultConstraintViolation = true;
    }

    @Override
    public String getDefaultConstraintMessageTemplate() {
        return getMessageTemplate().orElse(Objects.requireNonNull(constraint).getMessageTemplate());
    }

    @NonNull
    @Override
    public ClockProvider getClockProvider() {
        return defaultValidator.getClockProvider();
    }

    @Override
    public ConstraintViolationBuilder buildConstraintViolationWithTemplate(String messageTemplate) {
        return new DefaultConstraintViolationBuilder<>(messageTemplate, this, defaultValidator.messageInterpolator);
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        throw new ValidationException("Not supported");
    }

    @Override
    public void messageTemplate(@Nullable final String messageTemplate) {
        this.messageTemplate = messageTemplate;
    }

    Optional<String> getMessageTemplate() {
        return Optional.ofNullable(messageTemplate);
    }

    DefaultConstraintValidatorContext<R> copy() {
        return new DefaultConstraintValidatorContext<>(defaultValidator, beanIntrospection, validationContext, rootBean, executableReturnValue, new ValidationPath(currentPath), new LinkedHashSet<>(overallViolations), executableParameterValues, currentGroups);
    }

    @Internal
    interface GroupsValidation extends ValidationCloseable {

        boolean isFailed();
    }

    @Internal
    interface ValidationCloseable extends AutoCloseable {

        @Override
        void close();
    }

    @Internal
    record ValidationGroup(boolean isSequence, boolean isRedefinedDefaultGroupSequence,
                           List<Class<?>> groups) {
    }
}
