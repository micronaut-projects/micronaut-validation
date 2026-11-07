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
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The implementation of {@link ConstraintValidatorContext}.
 *
 * @param <R> The root bean type
 */
@Internal
public final class DefaultConstraintValidatorContext<R> implements ConstraintValidatorContext {

    private static final List<Class<?>> DEFAULT_GROUPS = Collections.singletonList(Default.class);
    private static final Class<?>[] EMPTY_GROUPS = new Class<?>[0];

    private boolean disableDefaultConstraintViolation;
    // the reports added so far, counted rather than read off the violations: equal reports are kept once
    private int reportedViolations;
    private @Nullable ConstraintDescriptor<Annotation> constraint;
    // the bean and value the current constraint validates, which a custom report keeps
    private @Nullable Object leafBean;
    private @Nullable Object validatedValue;

    private final BeanValidationContext validationContext;
    private final DefaultValidator defaultValidator;
    private final ReflectionSupport reflectionSupport;
    private final @Nullable BeanIntrospection<R> beanIntrospection;
    private final @Nullable R rootBean;
    @Nullable
    private Class<R> rootClass;
    // the spec tracks the instances already validated, not equal ones
    private final Set<Object> validatedObjects = Collections.newSetFromMap(new IdentityHashMap<>(20));
    private final ValidationPath currentPath;
    private final List<Class<?>> definedGroups;
    private @Nullable String messageTemplate;
    private final Set<ConstraintViolation<R>> overallViolations;

    // Contextual values
    private Object @Nullable [] executableParameterValues;
    private ElementType elementType = ElementType.FIELD;
    @Nullable
    private Class<?> unwrappedContainerType;
    @Nullable
    private Class<?> implicitGroup;
    @Nullable
    private Object executableReturnValue;
    private List<Class<?>> currentGroups;
    private Map<Class<?>, Class<?>> convertedGroups = Collections.emptyMap();
    // whether the current groups are the result of a conversion: each then lists the groups it extends
    // itself, see FindGroupContext#exact, and a constraint is in a group it names exactly
    private boolean currentGroupsExact;
    private List<AnnotationValue<ConvertGroup>> appliedConversions = List.of();
    private boolean hasCurrentViolations = false;
    // the groups of the step of a requested sequence being validated, which the beans it cascades to are
    // validated in; null outside such a step, the beans then validated in the groups requested
    @Nullable
    private List<Class<?>> stepGroups;

    DefaultConstraintValidatorContext(DefaultValidator defaultValidator, @Nullable BeanIntrospection<R> beanIntrospection, @Nullable R rootBean, BeanValidationContext validationContext) {
        this(defaultValidator, beanIntrospection, validationContext, rootBean, null, new ValidationPath(), new LinkedHashSet<>(), null, Collections.emptyList());
    }

    private DefaultConstraintValidatorContext(DefaultValidator defaultValidator,
                                              @Nullable BeanIntrospection<R> beanIntrospection,
                                              BeanValidationContext validationContext, @Nullable R rootBean,
                                              @Nullable Object executableReturnValue,
                                              ValidationPath path,
                                              Set<ConstraintViolation<R>> overallViolations,
                                              Object @Nullable [] executableParameterValues,
                                              List<Class<?>> currentGroups) {
        defaultValidator.checkOpen();
        this.validationContext = validationContext;
        this.defaultValidator = defaultValidator;
        this.reflectionSupport = defaultValidator.reflectionSupport();
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
     * The context of the validation of a constructor of a type without an introspection: no instance is the root
     * bean, the type is the root bean class.
     *
     * @param defaultValidator The validator
     * @param rootClass The type declaring the constructor
     * @param validationContext The validation context
     * @param <R> The type
     * @return The context
     */
    static <R> DefaultConstraintValidatorContext<R> forConstructor(DefaultValidator defaultValidator, Class<R> rootClass, BeanValidationContext validationContext) {
        DefaultConstraintValidatorContext<R> context = new DefaultConstraintValidatorContext<>(defaultValidator, null, null, validationContext);
        context.rootClass = rootClass;
        return context;
    }

    /**
     * The validation context.
     *
     * @return The context
     */
    public BeanValidationContext getValidationContext() {
        return validationContext;
    }

    /** @return The access provider captured by this validation context. */
    public ReflectionSupport reflectionSupport() {
        return reflectionSupport;
    }

    DefaultValidator defaultValidator() {
        return defaultValidator;
    }

    private List<Class<?>> processGroups(List<Class<?>> definedGroups) {
        if (CollectionUtils.isEmpty(definedGroups)) {
            return DEFAULT_GROUPS;
        }
        sanityCheckGroups(definedGroups);
        List<Class<?>> groups = new ArrayList<>();
        for (Class<?> group : definedGroups) {
            addInheritedGroups(group, groups);
        }
        return List.copyOf(groups);
    }

    private void addInheritedGroups(Class<?> group, List<Class<?>> groups) {
        if (!groups.contains(group)) {
            groups.add(group);
            if (reflectionSupport.canResolveHierarchy(group)) {
                for (Class<?> inherited : reflectionSupport.interfaces(group)) {
                    addInheritedGroups(inherited, groups);
                }
            }
        }
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

    private static boolean hasDefaultGroup(List<Class<?>> definedGroups) {
        return definedGroups.equals(DEFAULT_GROUPS);
    }

    public boolean containsGroup(Collection<Class<?>> constraintGroups) {
        if (implicitGroup != null && currentGroups.contains(implicitGroup)
            && (constraintGroups.isEmpty() || constraintGroups.contains(Default.class))) {
            // a constraint declared by an interface without a group is in the group of the
            // interface
            return true;
        }
        // loops rather than streams: this runs for every constraint of every validated element
        boolean exact = currentGroupsExact;
        if (constraintGroups.isEmpty()) {
            for (Class<?> group : currentGroups) {
                if (group == Default.class || (!exact && Default.class.isAssignableFrom(group))) {
                    return true;
                }
            }
            return false;
        }
        if (currentGroups.contains(Default.class) && rootClass != null && constraintGroups.contains(rootClass)) {
            return true;
        }
        for (Class<?> group : currentGroups) {
            for (Class<?> constraintGroup : constraintGroups) {
                if (constraintGroup == group || (!exact && constraintGroup.isAssignableFrom(group))) {
                    return true;
                }
            }
        }
        return false;
    }

    public Object @Nullable [] getExecutableParameterValues() {
        return executableParameterValues;
    }

    public @Nullable Object getExecutableReturnValue() {
        return executableReturnValue;
    }

    public boolean isValidated(Object obj) {
        return validatedObjects.contains(obj);
    }

    public ValidationCloseable validating(Object obj) {
        validatedObjects.add(obj);
        return () -> validatedObjects.remove(obj);
    }

    /**
     * Validates the member of a property: the traversable resolver is told which kind of member
     * declares the constraints, and the constraints a member of an interface declares belong to the
     * group of the interface.
     *
     * @param elementType The kind of member
     * @param implicitGroup The interface declaring the member, null for a class
     * @return The closeable restoring the previous member
     */
    public ValidationCloseable withMember(ElementType elementType, @Nullable Class<?> implicitGroup) {
        ElementType prevElementType = this.elementType;
        Class<?> prevImplicitGroup = this.implicitGroup;
        this.elementType = elementType;
        this.implicitGroup = implicitGroup;
        return () -> {
            this.elementType = prevElementType;
            this.implicitGroup = prevImplicitGroup;
        };
    }

    /**
     * The value of a container is unwrapped: the nodes of what it holds report the container it was
     * declared as.
     *
     * @param containerType The declared container type
     * @return The closeable restoring the previous state
     */
    public ValidationCloseable withUnwrappedContainer(Class<?> containerType) {
        Class<?> previous = this.unwrappedContainerType;
        this.unwrappedContainerType = containerType;
        return () -> this.unwrappedContainerType = previous;
    }

    /**
     * @param containerType The container type holding the values
     * @return The container type to report: the declared one when its value was unwrapped
     */
    public Class<?> reportedContainerType(Class<?> containerType) {
        return unwrappedContainerType != null ? unwrappedContainerType : containerType;
    }

    /**
     * @return The kind of member being validated, a field unless a getter is
     */
    public ElementType elementType() {
        return elementType;
    }

    public ValidationCloseable withExecutableParameterValues(Object[] executableParameterValues) {
        Object[] prevExecutableParameterValues = this.executableParameterValues;
        this.executableParameterValues = executableParameterValues;
        return () -> this.executableParameterValues = prevExecutableParameterValues;
    }

    public ValidationCloseable withExecutableReturnValue(@Nullable Object executableReturnValue) {
        Object prevExecutableReturnValue = this.executableReturnValue;
        this.executableReturnValue = executableReturnValue;
        return () -> this.executableReturnValue = prevExecutableReturnValue;
    }

    public GroupsValidation withGroupSequence(ValidationGroup validationGroup) {
        List<Class<?>> prevGroups = currentGroups;
        boolean prevViolations = hasCurrentViolations;
        boolean prevExact = currentGroupsExact;
        currentGroups = validationGroup.groups();
        currentGroupsExact = !convertedGroups.isEmpty();
        int violationsBefore = overallViolations.size();
        hasCurrentViolations = false;
        // a bean cascaded to from a step of a requested sequence fails that step with its violations; a redefined
        // default sequence is not ended by the beans it cascades to, which the reference implementation
        // validates apart from the sequence
        boolean cascadedFromSequenceStep = stepGroups != null;

        return new GroupsValidation() {

            @Override
            public boolean isFailed() {
                // According to the TCK, the validation with a group sequence
                // should fail immediately if group or a cascaded element has violations,
                // but redefined default groups should continue to the other group validation
                if (validationGroup.isRedefinedDefaultGroupSequence()) {
                    // the violations of this step, a cascaded element's included, not those of the groups before
                    return overallViolations.size() > violationsBefore;
                }
                // a group outside any sequence stops nothing: the sequences requested with it still run
                return validationGroup.isSequence() && hasCurrentViolations;
            }

            @Override
            public void close() {
                currentGroups = prevGroups;
                currentGroupsExact = prevExact;
                hasCurrentViolations = cascadedFromSequenceStep ? prevViolations || hasCurrentViolations : prevViolations;
            }
        };
    }

    /**
     * The beans cascaded to while a step of a requested sequence is validated are validated in the groups of the
     * step only, as the reference implementation does: each step of the sequence cascades in its own groups.
     *
     * @param validationGroup The step
     * @return The closeable restoring the previous groups
     */
    public ValidationCloseable cascadingIn(ValidationGroup validationGroup) {
        List<Class<?>> prevStepGroups = stepGroups;
        stepGroups = validationGroup.requested();
        return () -> stepGroups = prevStepGroups;
    }

    /**
     * A cascaded bean is entered: the conversions of its own relationships apply even when they equal the ones of
     * the relationship cascading to it.
     *
     * @return The closeable restoring the conversions of the relationship cascading
     */
    public ValidationCloseable enteringCascadedBean() {
        List<AnnotationValue<ConvertGroup>> prevAppliedConversions = appliedConversions;
        appliedConversions = List.of();
        return () -> appliedConversions = prevAppliedConversions;
    }

    private List<Class<?>> requestedGroups() {
        return stepGroups == null ? definedGroups : stepGroups;
    }

    public ValidationCloseable convertGroups(AnnotationMetadata annotationMetadata) {
        List<AnnotationValue<ConvertGroup>> conversions = annotationMetadata.getAnnotationValuesByType(ConvertGroup.class);
        if (conversions.isEmpty() || conversions.equals(appliedConversions)) {
            // the conversions of the relationship being cascaded, met again on its way in, are applied once
            return () -> {
            };
        }
        List<AnnotationValue<ConvertGroup>> prevAppliedConversions = appliedConversions;
        appliedConversions = conversions;
        Map<Class<?>, Class<?>> prevConvertedGroups = convertedGroups;
        List<Class<?>> prevGroups = currentGroups;

        Map<Class<?>, Class<?>> newConvertGroups = new HashMap<>();
        for (AnnotationValue<ConvertGroup> value : conversions) {
            GroupConversion conversion = GroupConversion.of(value);
            Class<?> to = conversion.to();
            if (to == null) {
                throw new NoSuchElementException("No value present");
            }
            Class<?> previous = newConvertGroups.putIfAbsent(conversion.from(), to);
            if (previous != null) {
                throw new IllegalStateException("Duplicate key " + conversion.from() + " (attempted merging values " + previous + " and " + to + ")");
            }
        }
        for (Class<?> group : prevGroups) {
            if (convertsInheritedGroup(newConvertGroups, group) && !reflectionSupport.canResolveHierarchy(group)) {
                throw ReflectionSupport.reflectionRequired("the hierarchy of the group " + group.getName()
                    + ", which converting an inherited group reads");
            }
        }
        // the conversions of the relationships cascaded so far, followed by this one: a group the previous
        // relationships converted is converted again, a group they left is converted from its original
        Map<Class<?>, Class<?>> composed = new HashMap<>(newConvertGroups);
        prevConvertedGroups.forEach((from, to) -> composed.put(from, convertGroup(newConvertGroups, to)));
        convertedGroups = composed;
        // only the cascaded bean is validated in the converted groups: the groups of the constraints declared
        // next to the conversion stay the ones requested
        return () -> {
            convertedGroups = prevConvertedGroups;
            appliedConversions = prevAppliedConversions;
        };
    }

    private static boolean convertsInheritedGroup(Map<Class<?>, Class<?>> conversions, Class<?> group) {
        for (Class<?> source : conversions.keySet()) {
            if (source != group && source.isAssignableFrom(group)) {
                return true;
            }
        }
        return false;
    }

    List<DefaultConstraintValidatorContext.ValidationGroup> findGroupSequences(@Nullable Object bean) {
        if (bean == null) {
            // no instance yet, a constructor or a value: the default group sequence is the one of
            // the validated type
            return beanIntrospection == null ? findGroupSequences() : findGroupSequences(beanIntrospection);
        } else {
            BeanIntrospection<?> beanIntrospection = defaultValidator.getBeanIntrospection(bean);
            if (beanIntrospection == null) {
                return findGroupSequences();
            } else {
                return findGroupSequences(beanIntrospection);
            }
        }
    }

    public List<ValidationGroup> findGroupSequences(BeanIntrospection<?> beanIntrospection) {
        FindGroupContext ctx = new FindGroupContext(defaultValidator, convertedGroups, requestedGroups());
        if (ctx.isDefault()) {
            return defaultValidator.declarations().defaultGroupSequences(beanIntrospection, bi -> List.copyOf(findGroupSequences(ctx, bi)));
        } else {
            return findGroupSequences(ctx, beanIntrospection);
        }
    }

    private static List<ValidationGroup> findGroupSequences(FindGroupContext requested, BeanIntrospection<?> beanIntrospection) {
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
        if (!ctx.definedGroups.contains(Default.class) || extendsDefaultGroup(ctx.definedGroups)) {
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
        List<ValidationGroup> dest = new ArrayList<>();
        List<Class<?>> others = new ArrayList<>(ctx.definedGroups.size());
        for (Class<?> group : ctx.definedGroups) {
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

    private static boolean extendsDefaultGroup(List<Class<?>> groups) {
        for (Class<?> group : groups) {
            if (group != Default.class && Default.class.isAssignableFrom(group)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The default group sequences the super types of a bean redefine, each applying to the
     * constraints its type declares: a redefinition is not inherited, it isolates the constraints
     * of the type redefining it.
     *
     * @param beanIntrospection The bean introspection
     * @return The sequences by the type declaring them, empty when the bean redefines its own or
     * the default group is not validated
     */
    public Map<Class<?>, List<ValidationGroup>> findIsolatedGroupSequences(BeanIntrospection<?> beanIntrospection) {
        FindGroupContext ctx = new FindGroupContext(defaultValidator, convertedGroups, requestedGroups());
        if (!hasDefaultGroup(ctx.definedGroups)
            || beanIntrospection.getAnnotationMetadata().classValues(GroupSequence.class).length > 0) {
            return Map.of();
        }
        Map<Class<?>, List<ValidationGroup>> isolated = new LinkedHashMap<>();
        for (BeanIntrospection<?> superIntrospection : defaultValidator.declarations().superIntrospections(beanIntrospection)) {
            Class<?> superType = superIntrospection.getBeanType();
            Class<Object>[] classGroupSequence = superIntrospection.getAnnotationMetadata().classValues(GroupSequence.class);
            if (!superType.isInterface() && classGroupSequence.length > 0) {
                validateDefaultGroupSequence(superType, classGroupSequence);
                isolated.put(superType, expandDefaultGroupSequence(ctx, superType, classGroupSequence));
            }
        }
        return isolated;
    }

    private static void validateDefaultGroupSequence(Class<?> defaultGroupType,
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

    private static List<ValidationGroup> expandDefaultGroupSequence(FindGroupContext ctx,
                                                                    Class<?> defaultGroupType,
                                                                    Class<Object>[] classGroupSequence) {
        List<ValidationGroup> dest = new ArrayList<>();
        for (Class<Object> group : classGroupSequence) {
            if (group == defaultGroupType) {
                dest.add(new ValidationGroup(true, true, List.of(Default.class), List.of(Default.class)));
            } else {
                int start = dest.size();
                findGroups(ctx, dest, List.of(group), new HashSet<>());
                // every group of the redefined default sequence is a step of it: a violation stops the sequence
                for (int i = start; i < dest.size(); i++) {
                    ValidationGroup step = dest.get(i);
                    if (!step.isSequence()) {
                        dest.set(i, new ValidationGroup(true, false, step.groups(), List.of(Default.class)));
                    } else {
                        dest.set(i, new ValidationGroup(true, step.isRedefinedDefaultGroupSequence(), step.groups(), List.of(Default.class)));
                    }
                }
            }
        }
        return dest;
    }

    public List<ValidationGroup> findGroupSequences() {
        FindGroupContext ctx = new FindGroupContext(defaultValidator, convertedGroups, requestedGroups());
        if (ctx.isDefault()) {
            return defaultValidator.declarations().defaultGroupSequences(null, ignored -> List.copyOf(findGroupSequences(ctx)));
        } else {
            return findGroupSequences(ctx);
        }
    }

    private static List<ValidationGroup> findGroupSequences(FindGroupContext ctx) {
        List<ValidationGroup> dest = new ArrayList<>();
        findGroups(ctx, dest, ctx.definedGroups, new HashSet<>());
        return dest;
    }

    private static void findGroups(FindGroupContext ctx, List<ValidationGroup> dest, Class<?> requestedGroup, Set<Class<?>> processedGroups) {
        Class<?> group = convertGroup(ctx.convertedGroups, requestedGroup);
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

    private static void expandGroup(FindGroupContext ctx, List<ValidationGroup> dest, Class<?> requestedGroup, Class<?> group, Set<Class<?>> processedGroups) {
        Class<?>[] groupSequence = ctx.defaultValidator.getBeanIntrospector().findIntrospection(group)
            .map(introspection -> (Class<?>[]) introspection.classValues(GroupSequence.class))
            .orElse(EMPTY_GROUPS);
        if (groupSequence.length == 0) {
            dest.add(new ValidationGroup(false, false, ctx.exact() ? exactGroups(ctx, requestedGroup) : List.of(group), List.of(requestedGroup)));
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
            ValidationGroup vg = dest.get(i);
            dest.set(i, new ValidationGroup(true, true, vg.groups, vg.requested));
        }
    }

    /**
     * The groups a group stands for below a conversion, listed one by one since they are matched exactly: the
     * group and each group it extends, every one converted on its own, a group converted to standing for itself
     * and the groups it extends. A group extending the default group keeps its own constraints when the
     * default group is converted, as in the reference implementation.
     */
    private static List<Class<?>> exactGroups(FindGroupContext ctx, Class<?> group) {
        List<Class<?>> groups = new ArrayList<>();
        for (Class<?> inherited : inheritedGroups(ctx, group)) {
            Class<?> converted = convertGroup(ctx.convertedGroups, inherited);
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
    private static List<Class<?>> inheritedGroups(FindGroupContext ctx, Class<?> group) {
        List<Class<?>> groups = new ArrayList<>();
        addInheritedGroups(ctx.defaultValidator.reflectionSupport(), group, groups);
        return groups;
    }

    private static void addInheritedGroups(ReflectionSupport reflectionSupport, Class<?> group, List<Class<?>> groups) {
        if (!groups.contains(group)) {
            groups.add(group);
            if (reflectionSupport.canResolveHierarchy(group)) {
                for (Class<?> inherited : reflectionSupport.interfaces(group)) {
                    addInheritedGroups(reflectionSupport, inherited, groups);
                }
            }
        }
    }

    private static Class<?> convertGroup(Map<Class<?>, Class<?>> convertedGroups, Class<?> group) {
        Class<?> newGroup = convertedGroups.get(group);
        if (newGroup == null) {
            return group;
        }
        return newGroup;
    }

    private static void findGroups(FindGroupContext ctx, List<ValidationGroup> dest, List<Class<?>> groupSequence, Set<Class<?>> processedGroups) {
        int start = dest.size();
        for (Class<?> g : groupSequence) {
            findGroups(ctx, dest, g, processedGroups);
        }
        boolean anySequence = false;
        for (int i = start; i < dest.size() && !anySequence; i++) {
            anySequence |= dest.get(i).isSequence;
        }
        if (!anySequence) {
            List<ValidationGroup> subList = dest.subList(start, dest.size());
            List<Class<?>> copy = new ArrayList<>();
            List<Class<?>> requested = new ArrayList<>();
            for (ValidationGroup validationGroup : subList) {
                copy.addAll(validationGroup.groups);
                requested.addAll(validationGroup.requested);
            }
            subList.clear();
            dest.add(new ValidationGroup(false, false, copy, requested));
        }
    }

    public void addViolation(DefaultConstraintViolation<R> violation) {
        reportedViolations++;
        hasCurrentViolations = true;
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

    public @Nullable Class<R> getRootClass() {
        return rootClass;
    }

    @Override
    public void disableDefaultConstraintViolation() {
        disableDefaultConstraintViolation = true;
    }

    boolean isDefaultConstraintViolationDisabled() {
        return disableDefaultConstraintViolation;
    }

    /**
     * @return The number of violations reported so far
     */
    int reportedViolations() {
        return reportedViolations;
    }

    /**
     * A constraint starts being validated: its validator may now build violations.
     *
     * @param constraint The constraint
     * @param leafBean The bean holding the validated value
     * @param validatedValue The validated value
     */
    void beginConstraint(ConstraintDescriptor<Annotation> constraint, @Nullable Object leafBean, @Nullable Object validatedValue) {
        this.constraint = constraint;
        this.leafBean = leafBean;
        this.validatedValue = validatedValue;
    }

    /**
     * The constraint begun last was validated: what its validator set is reset.
     */
    void endConstraint() {
        messageTemplate = null;
        constraint = null;
        leafBean = null;
        validatedValue = null;
        disableDefaultConstraintViolation = false;
    }

    @Nullable Object leafBean() {
        return leafBean;
    }

    @Nullable Object validatedValue() {
        return validatedValue;
    }

    @Override
    public String getDefaultConstraintMessageTemplate() {
        return getMessageTemplate().orElse(currentConstraint().getMessageTemplate());
    }

    /**
     * The constraint being validated: set for as long as its validator runs, which is when a
     * violation is built.
     *
     * @return The constraint
     */
    ConstraintDescriptor<Annotation> currentConstraint() {
        return Objects.requireNonNull(constraint, "No constraint is being validated");
    }

    @Override
    public ClockProvider getClockProvider() {
        return defaultValidator.getClockProvider();
    }

    @Override
    public ConstraintViolationBuilder buildConstraintViolationWithTemplate(String messageTemplate) {
        return new DefaultConstraintViolationBuilder<>(messageTemplate, this, defaultValidator.messageInterpolator());
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
        DefaultConstraintValidatorContext<R> copy = new DefaultConstraintValidatorContext<>(defaultValidator, beanIntrospection, validationContext, rootBean, executableReturnValue, new ValidationPath(currentPath), new LinkedHashSet<>(overallViolations), executableParameterValues, currentGroups);
        copy.rootClass = rootClass;
        copy.stepGroups = stepGroups;
        return copy;
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

    /**
     * A step of the validation.
     *
     * @param isSequence Whether the step belongs to a sequence, a violation then ending it
     * @param isRedefinedDefaultGroupSequence Whether the sequence is the default group sequence a bean redefines
     * @param groups The groups validated
     * @param requested The groups requested the step stands for, before any conversion: the groups the beans
     *                  cascaded to are validated in. The default group for every step of a redefined default
     *                  group sequence
     */
    @Internal
    record ValidationGroup(boolean isSequence, boolean isRedefinedDefaultGroupSequence,
                           List<Class<?>> groups, List<Class<?>> requested) {
    }

    /**
     * @param defaultValidator The validator
     * @param convertedGroups The conversions applying to the groups
     * @param definedGroups The groups requested
     * @param exact Whether the groups are validated below a conversion: each lists then the groups it extends
     */
    private record FindGroupContext(
        DefaultValidator defaultValidator,
        Map<Class<?>, Class<?>> convertedGroups,
        List<Class<?>> definedGroups,
        boolean exact
    ) {
        FindGroupContext(DefaultValidator defaultValidator, Map<Class<?>, Class<?>> convertedGroups, List<Class<?>> definedGroups) {
            this(defaultValidator, convertedGroups, definedGroups, !convertedGroups.isEmpty());
        }

        boolean isDefault() {
            return definedGroups == DEFAULT_GROUPS && convertedGroups.isEmpty();
        }
    }
}
