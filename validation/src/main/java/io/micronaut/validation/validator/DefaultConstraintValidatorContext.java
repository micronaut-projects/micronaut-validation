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
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintViolation;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The implementation of {@link ConstraintValidatorContext}.
 *
 * @param <R> The root bean type
 */
@Internal
public final class DefaultConstraintValidatorContext<R> implements ConstraintValidatorContext {

    static final List<Class<?>> DEFAULT_GROUPS = Collections.singletonList(Default.class);

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
    // itself, see GroupSequences.FindGroupContext#exact, and a constraint is in a group it names exactly
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
            GroupSequences.addInheritedGroups(reflectionSupport, group, groups);
        }
        return List.copyOf(groups);
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
     * Validates the steps in turn, each in its groups, until a step fails; only the first step cascades.
     *
     * @param steps The steps, see {@link #findGroupSequences()}
     * @param validation Validates a step, told whether it may cascade
     * @return Whether a step failed
     */
    boolean validateInGroups(List<ValidationGroup> steps, GroupStep validation) {
        boolean canCascade = true;
        for (ValidationGroup step : steps) {
            try (GroupsValidation groups = withGroupSequence(step)) {
                validation.validate(canCascade);
                if (groups.isFailed()) {
                    return true;
                }
            }
            canCascade = false;
        }
        return false;
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
        return convertGroups(annotationMetadata.getAnnotationValuesByType(ConvertGroup.class));
    }

    /**
     * Applies the group conversions of a relationship to the beans it cascades to.
     *
     * @param conversions The conversions, read off the metadata of the relationship
     * @return The closeable restoring the previous conversions
     */
    ValidationCloseable convertGroups(List<AnnotationValue<ConvertGroup>> conversions) {
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
                throw new ConstraintDeclarationException("The group conversion from " + conversion.from().getName() + " names no target group");
            }
            Class<?> previous = newConvertGroups.putIfAbsent(conversion.from(), to);
            if (previous != null) {
                throw new ConstraintDeclarationException("The group " + conversion.from().getName() + " is converted more than once, to "
                    + previous.getName() + " and " + to.getName());
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
        prevConvertedGroups.forEach((from, to) -> composed.put(from, newConvertGroups.getOrDefault(to, to)));
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
        GroupSequences.FindGroupContext ctx = new GroupSequences.FindGroupContext(defaultValidator, convertedGroups, requestedGroups());
        if (ctx.isDefault()) {
            return defaultValidator.declarations().defaultGroupSequences(beanIntrospection, bi -> List.copyOf(GroupSequences.findGroupSequences(ctx, bi)));
        } else {
            return GroupSequences.findGroupSequences(ctx, beanIntrospection);
        }
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
        GroupSequences.FindGroupContext ctx = new GroupSequences.FindGroupContext(defaultValidator, convertedGroups, requestedGroups());
        if (!hasDefaultGroup(ctx.definedGroups())) {
            return Map.of();
        }
        ValidatorDeclarations.BeanPlan plan = defaultValidator.declarations().beanPlan(beanIntrospection);
        if (plan.redefinesDefaultGroupSequence() || plan.superSequenceRedefinitions().isEmpty()) {
            return Map.of();
        }
        Map<Class<?>, List<ValidationGroup>> isolated = new LinkedHashMap<>();
        plan.superSequenceRedefinitions().forEach((superType, classGroupSequence) -> {
            GroupSequences.validateDefaultGroupSequence(superType, classGroupSequence);
            isolated.put(superType, GroupSequences.expandDefaultGroupSequence(ctx, superType, classGroupSequence));
        });
        return isolated;
    }

    public List<ValidationGroup> findGroupSequences() {
        GroupSequences.FindGroupContext ctx = new GroupSequences.FindGroupContext(defaultValidator, convertedGroups, requestedGroups());
        if (ctx.isDefault()) {
            return defaultValidator.declarations().defaultGroupSequences(null, ignored -> List.copyOf(GroupSequences.findGroupSequences(ctx)));
        } else {
            return GroupSequences.findGroupSequences(ctx);
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
        String template = messageTemplate;
        return template != null ? template : currentConstraint().getMessageTemplate();
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

    /**
     * @return The template the validator of the current constraint set, null for the template of the constraint
     */
    @Nullable String getMessageTemplate() {
        return messageTemplate;
    }

    DefaultConstraintValidatorContext<R> copy() {
        DefaultConstraintValidatorContext<R> copy = new DefaultConstraintValidatorContext<>(defaultValidator, beanIntrospection, validationContext, rootBean, executableReturnValue, new ValidationPath(currentPath), new LinkedHashSet<>(overallViolations), executableParameterValues, currentGroups);
        copy.rootClass = rootClass;
        copy.stepGroups = stepGroups;
        return copy;
    }

    /**
     * Validates in the groups of a step.
     */
    @FunctionalInterface
    interface GroupStep {

        /**
         * @param canCascade Whether the step may cascade: the first step alone does
         */
        void validate(boolean canCascade);
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
}
