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
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.beans.BeanPropertyMember;
import io.micronaut.core.type.Argument;
import io.micronaut.validation.validator.PropertyPlan.MemberPlan;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import io.micronaut.validation.validator.extractors.ValueExtractorDefinition;
import io.micronaut.validation.validator.extractors.ValueExtractorRegistry;
import io.micronaut.validation.validator.metadata.SeparatedPropertyDeclarations;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.TraversableResolver;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.ValidateUnwrappedValue;
import jakarta.validation.valueextraction.ValueExtractor;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Walks a validated value: its class-level constraints, its properties, the elements of its containers and the
 * beans it cascades to.
 *
 * @since 5.3.0
 */
@Internal
final class ValidationTraversal {

    private static final ValueExtractor<Object[]> LEGACY_ARRAY_EXTRACTOR = (originalValue, receiver) -> {
        int i = 0;
        for (Object item : originalValue) {
            receiver.indexedValue("<array element>", i++, item);
        }
    };

    private final DefaultValidator validator;
    private final ReflectionSupport reflectionSupport;
    private final BeanIntrospector beanIntrospector;
    private final ValueExtractorRegistry valueExtractorRegistry;
    private final TraversableResolver traversableResolver;
    // the default resolver answers true for every node: the paths it would be handed are not built
    private final boolean traversesAll;
    private final ValidatorDeclarations declarations;
    private final ConstraintEvaluator constraints;
    // by property: an introspection, an XML decorated one too, hands out the same property instances for its life
    private final Map<BeanProperty<?, ?>, PropertyPlan> propertyPlans = new ConcurrentHashMap<>();
    // the extractors of a container type as this validator compares them: the comparison depends on its introspector
    private final Map<Class<?>, List<ValueExtractorDefinition<?>>> valueExtractors = new ConcurrentHashMap<>();
    // the type argument of a declared container an extractor extracts, and the argument a super type binds
    private final Map<ExtractedArgumentKey, Optional<Integer>> extractedTypeArgumentIndexes = new ConcurrentHashMap<>();
    private final Map<ExtractedArgumentKey, Optional<Argument<?>>> boundTypeArguments = new ConcurrentHashMap<>();

    ValidationTraversal(DefaultValidator validator,
                        ReflectionSupport reflectionSupport,
                        ValueExtractorRegistry valueExtractorRegistry,
                        TraversableResolver traversableResolver,
                        ValidatorDeclarations declarations,
                        ConstraintEvaluator constraints) {
        this.validator = validator;
        this.reflectionSupport = reflectionSupport;
        this.beanIntrospector = reflectionSupport.introspector();
        this.valueExtractorRegistry = valueExtractorRegistry;
        this.traversableResolver = traversableResolver;
        this.traversesAll = traversableResolver instanceof DefaultValidatorConfiguration.TraverseAll;
        this.declarations = declarations;
        this.constraints = constraints;
    }

    void clear() {
        propertyPlans.clear();
        valueExtractors.clear();
        extractedTypeArgumentIndexes.clear();
        boundTypeArguments.clear();
    }

    <R, T> void doValidate(DefaultConstraintValidatorContext<R> context,
                           BeanIntrospection<T> introspection,
                           T object) {
        if (context.isValidated(object)) {
            return;
        }
        declarations.checkBeanDeclarations(introspection);
        boolean canCascade = true;
        try (DefaultConstraintValidatorContext.ValidationCloseable ignore = context.validating(object)) {
            Map<Class<?>, List<DefaultConstraintValidatorContext.ValidationGroup>> isolated = context.findIsolatedGroupSequences(introspection);
            List<DefaultConstraintValidatorContext.ValidationGroup> steps = context.findGroupSequences(introspection);
            // a requested sequence cascades in each step, in the groups of the step; a redefined default sequence
            // cascades once, in the default group
            boolean cascadePerStep = false;
            for (DefaultConstraintValidatorContext.ValidationGroup step : steps) {
                if (step.isSequence() && step.requested().contains(Default.class)) {
                    cascadePerStep = false;
                    break;
                }
                cascadePerStep |= step.isSequence();
            }
            // what the type and its super types declare, read once per type
            ValidatorDeclarations.BeanPlan beanPlan = declarations.beanPlan(introspection);
            AnnotationMetadata annotationMetadata = introspection.getAnnotationMetadata();
            boolean ownClassConstraints = beanPlan.ownClassConstraints();
            List<BeanIntrospection<?>> constrainedSuperIntrospections = beanPlan.constrainedSuperIntrospections();
            if (!isolated.isEmpty()) {
                List<BeanIntrospection<?>> notIsolated = new ArrayList<>(constrainedSuperIntrospections.size());
                for (BeanIntrospection<?> superIntrospection : constrainedSuperIntrospections) {
                    if (!isolated.containsKey(superIntrospection.getBeanType())) {
                        notIsolated.add(superIntrospection);
                    }
                }
                constrainedSuperIntrospections = notIsolated;
            }
            for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : steps) {
                if (cascadePerStep) {
                    canCascade = true;
                }
                try (DefaultConstraintValidatorContext.GroupsValidation validation = context.withGroupSequence(groupSequence);
                     DefaultConstraintValidatorContext.ValidationCloseable ignore3 = cascadePerStep ? context.cascadingIn(groupSequence) : () -> { }) {

                    if (ownClassConstraints) {
                        try (ValidationPath.ContextualPath ignore2 = context.getCurrentPath().addBeanNode()) {
                            visitElement(context, object, Argument.of(introspection.getBeanType(), annotationMetadata), annotationMetadata, object, false, Cascade.UNCHECKED);
                        }
                    }
                    // the class-level constraints of the super types, each type its own violations
                    for (BeanIntrospection<?> superIntrospection : constrainedSuperIntrospections) {
                        visitDeclaredSuperTypeConstraints(context, object, superIntrospection);
                    }
                    for (BeanProperty<T, Object> property : introspection.getBeanProperties()) {
                        visitProperty(context, introspection, object, property, canCascade, type -> !isolated.containsKey(type));
                    }

                    if (validation.isFailed()) {
                        break;
                    }
                }
                canCascade = false;
            }
            // the constraints a super type redefining the default group sequence declares follow that sequence
            for (Map.Entry<Class<?>, List<DefaultConstraintValidatorContext.ValidationGroup>> entry : isolated.entrySet()) {
                context.validateInGroups(entry.getValue(), isolatedCanCascade -> {
                    for (BeanIntrospection<?> superIntrospection : beanPlan.constrainedSuperIntrospections()) {
                        if (superIntrospection.getBeanType() == entry.getKey()) {
                            visitDeclaredSuperTypeConstraints(context, object, superIntrospection);
                        }
                    }
                    for (BeanProperty<T, Object> property : introspection.getBeanProperties()) {
                        visitProperty(context, introspection, object, property, isolatedCanCascade, type -> type == entry.getKey());
                    }
                });
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <E> List<ValueExtractorDefinition<E>> findValueExtractors(Class<E> containerType) {
        List<ValueExtractorDefinition<?>> found = valueExtractors.get(containerType);
        if (found == null) {
            // the extractors compared by the type argument of the container each extracts
            found = (List) valueExtractorRegistry.findValueExtractors(containerType, definition -> {
                try {
                    return extractedTypeArgumentIndex(containerType, definition);
                } catch (ValidationException e) {
                    // a runtime type without metadata, a JDK collection: its extractors meet by their own argument
                    return definition.typeArgumentIndex();
                }
            });
            valueExtractors.put(containerType, found);
        }
        return (List) found;
    }

    private @Nullable Integer extractedTypeArgumentIndex(Class<?> declaredType, ValueExtractorDefinition<?> definition) {
        ExtractedArgumentKey key = new ExtractedArgumentKey(declaredType, definition.containerType(), definition.typeArgumentIndex());
        Optional<Integer> index = extractedTypeArgumentIndexes.get(key);
        if (index == null) {
            // a failed resolution is not cached: it throws again
            index = Optional.ofNullable(ContainerTypeArguments.resolveExtractedTypeArgumentIndex(reflectionSupport,
                beanIntrospector, declaredType, definition.containerType(), definition.typeArgumentIndex()));
            extractedTypeArgumentIndexes.put(key, index);
        }
        return index.orElse(null);
    }

    private @Nullable Argument<?> boundTypeArgument(Class<?> declaredType, ValueExtractorDefinition<?> definition, int typeArgumentIndex) {
        ExtractedArgumentKey key = new ExtractedArgumentKey(declaredType, definition.containerType(), typeArgumentIndex);
        Optional<Argument<?>> bound = boundTypeArguments.get(key);
        if (bound == null) {
            bound = Optional.ofNullable(ContainerTypeArguments.resolveBoundTypeArgument(reflectionSupport,
                beanIntrospector, declaredType, definition.containerType(), typeArgumentIndex));
            boundTypeArguments.put(key, bound);
        }
        return bound.orElse(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <R, T> void visitDeclaredSuperTypeConstraints(DefaultConstraintValidatorContext<R> context,
                                                          T object,
                                                          BeanIntrospection<?> superIntrospection) {
        AnnotationMetadata superMetadata = superIntrospection.getAnnotationMetadata();
        try (ValidationPath.ContextualPath ignore = context.getCurrentPath().addBeanNode()) {
            visitElement(context, object, Argument.of((Class) superIntrospection.getBeanType(), superMetadata), superMetadata, object, false, Cascade.UNCHECKED);
        }
    }

    <R, T> void visitProperty(DefaultConstraintValidatorContext<R> context,
                              BeanIntrospection<T> introspection,
                              T object,
                              BeanProperty<T, Object> property,
                              boolean canCascade) {
        visitProperty(context, introspection, object, property, canCascade, type -> true);
    }

    /**
     * Visits the members of a property the types the filter accepts declare: a type redefining the default
     * group sequence validates its members in its own passes.
     */
    private <R, T> void visitProperty(DefaultConstraintValidatorContext<R> context,
                                      BeanIntrospection<T> introspection,
                                      T object,
                                      BeanProperty<T, Object> property,
                                      boolean canCascade,
                                      Predicate<Class<?>> declaringTypes) {
        if (property.isWriteOnly()) {
            return;
        }
        String propertyName = property.getName();
        Class<?> beanType = object.getClass();
        PropertyPlan plan = propertyPlan(introspection, property);
        if (!plan.members().isEmpty()) {
            // each member against the value it holds; the value is cascaded once, by the first member marking it so
            boolean cascadeLeft = canCascade;
            for (MemberPlan member : plan.members()) {
                if (declaringTypes.test(member.member().getDeclaringType())) {
                    visitPropertyMember(context, object, property, member, cascadeLeft);
                }
                if (member.cascaded()) {
                    cascadeLeft = false;
                }
            }
            return;
        }
        if (!plan.validated() || !declaringTypes.test(beanType)) {
            // the getter is never called and the traversable resolver never asked
            return;
        }
        try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addPropertyNode(propertyName)) {
            if (isNotReachable(context, object) ||
                !context.getValidationContext().isPropertyValidated(object, property)) {
                return;
            }
            AnnotationMetadata annotationMetadata = property.getAnnotationMetadata();
            try (DefaultConstraintValidatorContext.ValidationCloseable ignore = context.convertGroups(plan.conversions())) {
                Object propertyValue;
                try {
                    propertyValue = property.get(object);
                } catch (Exception e) {
                    throw new ValidationException("Failed to get the value of property: " + propertyName, e);
                }
                visitElement(context, object, plan.argument(), annotationMetadata, propertyValue, canCascade, Cascade.CHECKED);
            }
        }
    }

    /**
     * What validating a property reads from its metadata, prepared once per property. The arguments are the same
     * instances on every call, which is what lets the constraints of their metadata be cached.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private PropertyPlan propertyPlan(BeanIntrospection<?> introspection, BeanProperty<?, Object> property) {
        PropertyPlan plan = propertyPlans.get(property);
        if (plan != null) {
            return plan;
        }
        return propertyPlans.computeIfAbsent(property, p -> {
            AnnotationMetadata propertyMetadata = property.getAnnotationMetadata();
            Argument<Object> argument = argumentWithMetadata(property.asArgument(), propertyMetadata);
            List<AnnotationValue<ConvertGroup>> conversions = propertyMetadata.getAnnotationValuesByType(ConvertGroup.class);
            boolean validated = isValidated(propertyMetadata, argument, conversions);
            if (!reflectionSupport.separatesDeclarations(introspection)
                && !(property instanceof SeparatedPropertyDeclarations)) {
                // a description merging the declarations into the property validates the property alone
                return new PropertyPlan(argument, validated, conversions, List.of());
            }
            // a generated introspection reports members only where the type asked for them and merges what they
            // declare into the property: walking them twice would validate them twice
            List<MemberPlan> members = new ArrayList<>();
            for (BeanPropertyMember<?, ?> member : property.getMembers()) {
                if (!member.isReadable() || !isValidatedMember(member)) {
                    continue;
                }
                AnnotationMetadata memberMetadata = member.getAnnotationMetadata();
                Argument<?> typed = member.asArgument();
                boolean cascaded = memberMetadata.hasStereotype(Valid.class)
                    || ArgumentValidationMetadata.hasCascadedTypeArgument(reflectionSupport, typed);
                members.add(new MemberPlan((BeanPropertyMember) member,
                    (Argument<Object>) typed.withName(property.getName()).withAnnotationMetadata(memberMetadata),
                    cascaded, memberMetadata.getAnnotationValuesByType(ConvertGroup.class)));
            }
            return new PropertyPlan(argument, validated, conversions, List.copyOf(members));
        });
    }

    @SuppressWarnings("unchecked")
    private <R, T> void visitPropertyMember(DefaultConstraintValidatorContext<R> context,
                                            T object,
                                            BeanProperty<T, Object> property,
                                            MemberPlan plan,
                                            boolean canCascade) {
        BeanPropertyMember<T, ?> member = (BeanPropertyMember<T, ?>) plan.member();
        try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addPropertyNode(property.getName())) {
            Class<?> implicitGroup = member.getDeclaringType().isInterface() ? member.getDeclaringType() : null;
            try (DefaultConstraintValidatorContext.ValidationCloseable ignore = context.withMember(member.getElementType(), implicitGroup)) {
                if (isNotReachable(context, object) ||
                    !context.getValidationContext().isPropertyValidated(object, property)) {
                    return;
                }
                AnnotationMetadata memberMetadata = member.getAnnotationMetadata();
                try (DefaultConstraintValidatorContext.ValidationCloseable ignore2 = context.convertGroups(plan.conversions())) {
                    Object value;
                    try {
                        value = reflectionSupport.readMember(member, property, object);
                    } catch (Exception e) {
                        throw new ValidationException("Failed to get the value of property: " + property.getName(), e);
                    }
                    visitElement(context, object, plan.argument(), memberMetadata, value, canCascade, Cascade.CHECKED);
                }
            }
        }
    }

    private boolean isValidatedMember(BeanPropertyMember<?, ?> member) {
        AnnotationMetadata annotationMetadata = member.getAnnotationMetadata();
        return !member.isSynthetic()
            && isValidated(annotationMetadata, member.asArgument(), annotationMetadata.getAnnotationValuesByType(ConvertGroup.class));
    }

    /**
     * Whether an element declares constraints, a cascade, constrained type arguments or group conversions.
     */
    private boolean isValidated(AnnotationMetadata annotationMetadata, Argument<?> argument, List<AnnotationValue<ConvertGroup>> conversions) {
        return ConstraintContainers.hasConstraints(reflectionSupport, annotationMetadata, reflectionSupport.classLoader())
            || annotationMetadata.hasStereotype(Valid.class)
            || ArgumentValidationMetadata.hasValidatedTypeArgument(reflectionSupport, argument)
            || !conversions.isEmpty();
    }

    <R, T> boolean isNotReachable(DefaultConstraintValidatorContext<R> context, @Nullable T object) {
        if (traversesAll) {
            return false;
        }
        ValidationPath currentPath = context.getCurrentPath();
        ValidationPath previousPath = currentPath.previousPath();
        try {
            return !traversableResolver.isReachable(
                    object,
                    currentPath.last(),
                    context.getRootClass(),
                    previousPath,
                    context.elementType()
            );
        } catch (Exception e) {
            throw new ValidationException("Cannot call 'isReachable' on traversableResolver: " + traversableResolver, e);
        }
    }

    private <R> boolean isCascadable(DefaultConstraintValidatorContext<R> context, @Nullable Object leftBean) {
        if (traversesAll) {
            return true;
        }
        try {
            ValidationPath currentPath = context.getCurrentPath();
            ValidationPath previousPath = currentPath.previousPath();
            return traversableResolver.isCascadable(
                    leftBean,
                    currentPath.last(),
                    context.getRootClass(),
                    previousPath,
                    context.elementType()
            );
        } catch (Exception e) {
            throw new ValidationException("Cannot call 'isCascadable' on traversableResolver: " + traversableResolver, e);
        }
    }

    /**
     * Visits an element declaring a cascade with {@link Valid} alone, the traversable resolver asked before it
     * cascades.
     */
    <R, E> void visitElement(DefaultConstraintValidatorContext<R> context,
                             @Nullable Object bean,
                             Argument<E> elementArgument,
                             @Nullable E elementValue,
                             boolean canCascade) {
        visitElement(context, bean, elementArgument, elementArgument.getAnnotationMetadata(), elementValue, canCascade, Cascade.CHECKED);
    }

    /**
     * Visits an element: its constraints, the elements of a container, and the bean it cascades to.
     *
     * @param context The context
     * @param leftBean The bean holding the element
     * @param elementArgument The element
     * @param annotationMetadata The metadata declaring the constraints of the element
     * @param elementValue The value
     * @param canCascade Whether the element may cascade in the groups being validated
     * @param cascade How the element cascades
     * @param <R> The root bean type
     * @param <E> The element type
     */
    <R, E> void visitElement(DefaultConstraintValidatorContext<R> context,
                             @Nullable Object leftBean,
                             Argument<E> elementArgument,
                             AnnotationMetadata annotationMetadata,
                             @Nullable E elementValue,
                             boolean canCascade,
                             Cascade cascade) {
        boolean cascaded = canCascade && (cascade == Cascade.LEGACY || annotationMetadata.hasStereotype(Valid.class));
        List<DefaultConstraintDescriptor<Annotation>> elementConstraints = constraints.getConstraints(context, annotationMetadata);

        if (visitContainer(context, leftBean, elementArgument, annotationMetadata, elementValue, elementConstraints, canCascade)) {
            return;
        }

        constraints.validateConstraints(context, leftBean, elementArgument, elementValue, elementConstraints);

        if (cascaded && elementValue != null) {
            try (DefaultConstraintValidatorContext.ValidationCloseable ignore = context.convertGroups(elementArgument.getAnnotationMetadata())) {
                propagateValidation(context, leftBean, elementArgument, elementValue, cascade != Cascade.UNCHECKED);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <R, E> boolean visitContainer(DefaultConstraintValidatorContext<R> context,
                                          @Nullable Object leftBean,
                                          Argument<E> containerArgument,
                                          AnnotationMetadata annotationMetadata,
                                          @Nullable E containerValue,
                                          List<DefaultConstraintDescriptor<Annotation>> elementConstraints,
                                          boolean canCascade) {
        if (!ArgumentValidationMetadata.isValidated(reflectionSupport, containerArgument) && !ArgumentValidationMetadata.hasValidatedTypeArgument(reflectionSupport, containerArgument)) {
            return false;
        }
        boolean isLegacyValid = annotationMetadata.hasAnnotation(Valid.class)
            && (Iterable.class.isAssignableFrom(containerArgument.getType())
            || Map.class.isAssignableFrom(containerArgument.getType())
            || Object[].class.isAssignableFrom(containerArgument.getType())
        );
        boolean anyExplicitUnwrapping = false;
        for (DefaultConstraintDescriptor<Annotation> constraint : elementConstraints) {
            if (constraint.getValueUnwrapping() == ValidateUnwrappedValue.UNWRAP) {
                anyExplicitUnwrapping = true;
                break;
            }
        }

        Class<E> extractorLookupType = containerArgument.getType();
        // a cascade follows the runtime type of the container, a declared Iterable holding a List is indexed
        if (containerValue != null
            && (isLegacyValid || ArgumentValidationMetadata.hasCascadedTypeArgument(reflectionSupport, containerArgument))
            && !hasConstrainedTypeArgument(context, containerArgument)) {
            extractorLookupType = (Class<E>) containerValue.getClass();
        }
        List<ValueExtractorDefinition<E>> valueExtractorDefinitions = findValueExtractors(extractorLookupType);
        if (valueExtractorDefinitions.isEmpty()) {
            if (isLegacyValid && Object[].class.isAssignableFrom(containerArgument.getType())) {
                // a legacy value extractor for an array
                containerArgument = (Argument<E>) Argument.of(Object[].class, containerArgument.getAnnotationMetadata());
                valueExtractorDefinitions = List.of(
                    (ValueExtractorDefinition<E>) new ValueExtractorDefinition<>(Object[].class, Object.class, null, false, LEGACY_ARRAY_EXTRACTOR)
                );
            } else {
                if (anyExplicitUnwrapping) {
                    throw new ConstraintDeclarationException("Cannot unwrap the constraint no extractors are present!");
                }
                if (ArgumentValidationMetadata.hasValidatedTypeArgument(reflectionSupport, containerArgument)) {
                    throw new ConstraintDeclarationException(
                            "Cannot validate container element constraints without a value"
                                + " extractor for "
                                    + containerArgument.getType().getName());
                }
                return false;
            }
        }
        validateValueExtractorCoverage(containerArgument, valueExtractorDefinitions);
        if (!isLegacyValid && annotationMetadata.hasAnnotation(Valid.class)
            && !ArgumentValidationMetadata.hasCascadedTypeArgument(reflectionSupport, containerArgument)) {
            // @Valid on any other container a value extractor reads, an Optional, cascades into its values as the
            // reference implementation does; deprecated in favour of @Valid on the type argument
            isLegacyValid = true;
        }

        if (anyExplicitUnwrapping && valueExtractorDefinitions.size() > 1) {
            throw new ConstraintDeclarationException("Cannot unwrap the constraint when multiple value extractors are present!");
        }

        ValueExtractorDefinition<E> singleUnwrapByDefault = null;
        for (ValueExtractorDefinition<E> definition : valueExtractorDefinitions) {
            if (definition.unwrapByDefault()) {
                if (singleUnwrapByDefault != null) {
                    throw new ConstraintDeclarationException("Multiple unwrap by default value extractors aren't allowed!");
                }
                singleUnwrapByDefault = definition;
            }
        }

        List<DefaultConstraintDescriptor<Annotation>> containerElementConstraints = new ArrayList<>();
        List<DefaultConstraintDescriptor<Annotation>> containerConstraints = new ArrayList<>();
        if (singleUnwrapByDefault != null) {
            if (valueExtractorDefinitions.size() != 1) {
                // only one extractor unwrapping by default
                valueExtractorDefinitions = List.of(singleUnwrapByDefault);
            }
            for (DefaultConstraintDescriptor<Annotation> constraint : elementConstraints) {
                if (constraint.getValueUnwrapping() == ValidateUnwrappedValue.SKIP) {
                    containerConstraints.add(constraint);
                } else {
                    containerElementConstraints.add(constraint);
                }
            }
        } else {
            for (DefaultConstraintDescriptor<Annotation> constraint : elementConstraints) {
                if (constraint.getValueUnwrapping() == ValidateUnwrappedValue.UNWRAP) {
                    containerElementConstraints.add(constraint);
                } else {
                    containerConstraints.add(constraint);
                }
            }
        }
        constraints.validateConstraints(context, leftBean, containerArgument, containerValue, containerConstraints);

        for (ValueExtractorDefinition<E> valueExtractorDefinition : valueExtractorDefinitions) {

            // a legacy @Valid on a map cascades its values only; its keys keep the constraints declared on them
            boolean legacyKeys = isLegacyValid && valueExtractorDefinition.containerType().equals(Map.class)
                && Objects.equals(valueExtractorDefinition.typeArgumentIndex(), 0);
            boolean legacy = isLegacyValid && !legacyKeys;

            Integer typeArgumentIndex = valueExtractorDefinition.typeArgumentIndex();
            Integer declaredTypeArgumentIndex = extractedTypeArgumentIndex(containerArgument.getType(), valueExtractorDefinition);
            Argument<Object> containerValueArgument;
            boolean unwrapping = false;
            Argument[] typeParameters = containerArgument.getTypeParameters();
            if (declaredTypeArgumentIndex != null && declaredTypeArgumentIndex >= 0 && typeParameters.length > 0 && declaredTypeArgumentIndex < typeParameters.length) {
                containerValueArgument = typeParameters[declaredTypeArgumentIndex];
            } else {
                // a container without type arguments of its own binds the extracted one in a generic super type
                Argument<?> bound = typeArgumentIndex == null ? null
                    : boundTypeArgument(containerArgument.getType(), valueExtractorDefinition, typeArgumentIndex);
                unwrapping = typeArgumentIndex == null;
                if (bound != null) {
                    containerValueArgument = (Argument<Object>) bound;
                } else if (typeArgumentIndex == null) {
                    // an unwrapped value keeps the type arguments declared on the container wrapping it
                    containerValueArgument = (Argument<Object>) Argument.of((Class) valueExtractorDefinition.valueType(), containerArgument.getName(), AnnotationMetadata.EMPTY_METADATA, typeParameters);
                } else {
                    containerValueArgument = (Argument<Object>) Argument.of((Class) valueExtractorDefinition.valueType());
                }
                declaredTypeArgumentIndex = null;
            }
            boolean elementValidated = ArgumentValidationMetadata.isValidated(reflectionSupport, containerValueArgument)
                || ArgumentValidationMetadata.hasValidatedTypeArgument(reflectionSupport, containerValueArgument);
            if (legacyKeys && !elementValidated) {
                continue;
            }
            if (!elementValidated && containerElementConstraints.isEmpty() && !legacy) {
                // nothing to validate at this level nor deeper: a generated metadata marks the levels, a reflective one does not
                continue;
            }

            if (containerValue == null) {
                constraints.validateConstraints(context, leftBean, containerValueArgument, null, containerElementConstraints);
                continue;
            }

            ValueExtractor<E> valueExtractor = valueExtractorDefinition.valueExtractor();
            try {
                valueExtractor.extractValues(containerValue, new ContainerValueReceiver<>(context, leftBean,
                    containerArgument.getType(), declaredTypeArgumentIndex, unwrapping, containerValueArgument,
                    containerElementConstraints, canCascade, legacy));
            } catch (ValidationException e) {
                throw e;
            } catch (Exception e) {
                throw new ValidationException("Exception extracting values using: " + valueExtractor, e);
            }
        }

        return true;
    }

    private boolean hasConstrainedTypeArgument(DefaultConstraintValidatorContext<?> context, Argument<?> argument) {
        for (Argument<?> typeParameter : argument.getTypeParameters()) {
            if (!constraints.getConstraints(context, typeParameter.getAnnotationMetadata()).isEmpty()
                || hasConstrainedTypeArgument(context, typeParameter)) {
                return true;
            }
        }
        return false;
    }

    private void validateValueExtractorCoverage(Argument<?> containerArgument,
                                                List<? extends ValueExtractorDefinition<?>> valueExtractorDefinitions) {
        Argument<?>[] typeParameters = containerArgument.getTypeParameters();
        for (int i = 0; i < typeParameters.length; i++) {
            if (ArgumentValidationMetadata.isValidated(reflectionSupport, typeParameters[i]) && !hasValueExtractorForTypeArgument(containerArgument.getType(), valueExtractorDefinitions, i)) {
                throw new ConstraintDeclarationException(
                        "Cannot validate container element constraints without a value extractor"
                            + " for type argument "
                                + i + " of " + containerArgument.getType().getName());
            }
        }
    }

    private boolean hasValueExtractorForTypeArgument(Class<?> declaredType,
                                                     List<? extends ValueExtractorDefinition<?>> valueExtractorDefinitions,
                                                     int typeArgumentIndex) {
        for (ValueExtractorDefinition<?> valueExtractorDefinition : valueExtractorDefinitions) {
            Integer declaredTypeArgumentIndex = extractedTypeArgumentIndex(declaredType, valueExtractorDefinition);
            if (Objects.equals(declaredTypeArgumentIndex, typeArgumentIndex)) {
                return true;
            }
        }
        return false;
    }

    private <R, E> void propagateValidation(DefaultConstraintValidatorContext<R> context,
                                            @Nullable Object leftBean,
                                            Argument<E> elementType,
                                            E elementValue,
                                            boolean checkCascadable) {
        final BeanIntrospection<E> beanIntrospection = validator.getBeanIntrospection(elementValue, elementType.getType());
        if (beanIntrospection == null) {
            // a cascaded value without an introspection is a violation
            context.addViolation(constraints.createConstraintViolation(context, leftBean, elementValue,
                new NotIntrospectedConstraintDescriptor<>(reflectionSupport, elementType, elementValue)));
            return;
        }
        if (!checkCascadable || isCascadable(context, leftBean)) {
            try (ValidationPath.ContextualPath ignore = context.getCurrentPath().cascaded();
                 DefaultConstraintValidatorContext.ValidationCloseable ignore2 = context.enteringCascadedBean()) {
                doValidate(context, beanIntrospection, elementValue);
            }
        }
    }

    private static <T> Argument<T> argumentWithMetadata(Argument<T> argument, AnnotationMetadata annotationMetadata) {
        return Argument.of(argument.getType(), annotationMetadata, argument.getTypeParameters());
    }

    /**
     * Receives the values a value extractor reads off a container: each is validated against the constraints of
     * the element and visited as an element of its own.
     *
     * @param <R> The root bean type
     */
    private final class ContainerValueReceiver<R> implements ValueExtractor.ValueReceiver {

        private final DefaultConstraintValidatorContext<R> context;
        private final @Nullable Object leftBean;
        private final Class<?> containerType;
        private final @Nullable Integer typeArgumentIndex;
        private final boolean unwrapping;
        private final Argument<Object> valueArgument;
        private final List<DefaultConstraintDescriptor<Annotation>> elementConstraints;
        private final boolean canCascade;
        private final boolean legacy;

        ContainerValueReceiver(DefaultConstraintValidatorContext<R> context,
                               @Nullable Object leftBean,
                               Class<?> containerType,
                               @Nullable Integer typeArgumentIndex,
                               boolean unwrapping,
                               Argument<Object> valueArgument,
                               List<DefaultConstraintDescriptor<Annotation>> elementConstraints,
                               boolean canCascade,
                               boolean legacy) {
            this.context = context;
            this.leftBean = leftBean;
            this.containerType = containerType;
            this.typeArgumentIndex = typeArgumentIndex;
            this.unwrapping = unwrapping;
            this.valueArgument = valueArgument;
            this.elementConstraints = elementConstraints;
            this.canCascade = canCascade;
            this.legacy = legacy;
        }

        @Override
        public void value(String nodeName, Object value) {
            validateContainerValue(nodeName, ValidationPath.ContainerContext.value(context.reportedContainerType(containerType), typeArgumentIndex), value);
        }

        @Override
        public void iterableValue(String nodeName, Object iterableValue) {
            validateContainerValue(nodeName, ValidationPath.ContainerContext.iterable(context.reportedContainerType(containerType), typeArgumentIndex), iterableValue);
        }

        @Override
        public void indexedValue(String nodeName, int index, Object iterableValue) {
            validateContainerValue(nodeName, ValidationPath.ContainerContext.indexed(context.reportedContainerType(containerType), index, typeArgumentIndex), iterableValue);
        }

        @Override
        public void keyedValue(String nodeName, Object key, Object value) {
            validateContainerValue(nodeName, ValidationPath.ContainerContext.keyed(context.reportedContainerType(containerType), key, typeArgumentIndex), value);
        }

        private void validateContainerValue(@Nullable String name, ValidationPath.ContainerContext containerContext, Object value) {
            if (name != null && !legacy) {
                try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addContainerElementNode(name, containerContext)) {
                    validateContainerValue(value);
                }
            } else {
                try (ValidationPath.ContextualPath ignored = context.getCurrentPath().withContainerContext(containerContext)) {
                    validateContainerValue(value);
                }
            }
        }

        private void validateContainerValue(Object value) {
            constraints.validateConstraints(context, leftBean, valueArgument, value, elementConstraints);
            try (DefaultConstraintValidatorContext.ValidationCloseable ignored = unwrapping
                ? context.withUnwrappedContainer(context.reportedContainerType(containerType))
                : () -> { }) {
                // the type arguments are those of an argument prepared once: their metadata is cached by identity
                visitElement(context, leftBean, valueArgument, valueArgument.getAnnotationMetadata(), value, canCascade, legacy ? Cascade.LEGACY : Cascade.CHECKED);
            }
        }
    }

    /**
     * How an element cascades.
     */
    enum Cascade {
        /** With a {@link Valid} of its own, the traversable resolver asked first. */
        CHECKED,
        /** With a {@link Valid} of its own, the traversable resolver not asked: a parameter or a return value. */
        UNCHECKED,
        /**
         * Without a {@link Valid} of its own, the traversable resolver asked first: an element of a container
         * marked the legacy way.
         */
        LEGACY
    }

    private record ExtractedArgumentKey(Class<?> declaredType, Class<?> extractorContainerType, @Nullable Integer typeArgumentIndex) {
    }
}
