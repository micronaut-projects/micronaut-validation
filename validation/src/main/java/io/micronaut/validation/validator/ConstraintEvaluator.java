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
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import io.micronaut.validation.validator.constraints.ConstraintValidatorRegistry;
import io.micronaut.validation.validator.constraints.ConstraintValidatorTargetResolver;
import io.micronaut.validation.validator.messages.DefaultMessageInterpolatorContext;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.UnexpectedTypeException;
import jakarta.validation.ValidationException;
import jakarta.validation.metadata.ConstraintDescriptor;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Evaluates the constraints of an element: resolves their validators, runs them and reports the violations.
 *
 * @since 5.3.0
 */
@Internal
final class ConstraintEvaluator {

    private static final int MAX_CACHED_CONSTRAINTS = 16 * 1024;

    private final ReflectionSupport reflectionSupport;
    private final ConstraintValidatorRegistry constraintValidatorRegistry;
    private final ConstraintValidatorOverrides validatorOverrides;
    private final ConstraintValidatorInstances validatorInstances;
    private final ValidatorDeclarations declarations;
    private final MessageInterpolator messageInterpolator;
    // The constraints of an element, by the identity of its metadata: the metadata of the walked elements is
    // prepared once, so the keys are few. Dropped whole past MAX_CACHED_CONSTRAINTS rather than copied on insert.
    private final ConcurrentMap<AnnotationMetadata, List<DefaultConstraintDescriptor<Annotation>>> constraintCache =
        new ConcurrentHashMap<>();

    ConstraintEvaluator(ReflectionSupport reflectionSupport,
                        ConstraintValidatorRegistry constraintValidatorRegistry,
                        ConstraintValidatorOverrides validatorOverrides,
                        ConstraintValidatorInstances validatorInstances,
                        ValidatorDeclarations declarations,
                        MessageInterpolator messageInterpolator) {
        this.reflectionSupport = reflectionSupport;
        this.constraintValidatorRegistry = constraintValidatorRegistry;
        this.validatorOverrides = validatorOverrides;
        this.validatorInstances = validatorInstances;
        this.declarations = declarations;
        this.messageInterpolator = messageInterpolator;
    }

    void clear() {
        constraintCache.clear();
    }

    /**
     * Validates the constraints the metadata of an element declares in the groups being validated.
     */
    <R, E> void validateConstraints(DefaultConstraintValidatorContext<R> context,
                                    @Nullable Object leftBean,
                                    Argument<E> elementArgument,
                                    @Nullable E elementValue) {
        validateConstraints(context, leftBean, elementArgument, elementValue, getConstraints(context, elementArgument.getAnnotationMetadata()));
    }

    <R, E> void validateConstraints(DefaultConstraintValidatorContext<R> context,
                                    @Nullable Object leftBean,
                                    Argument<E> elementArgument,
                                    @Nullable E elementValue,
                                    List<DefaultConstraintDescriptor<Annotation>> constraints) {
        if (constraints.isEmpty()) {
            // no validator runs, so the lifecycle lock is not taken
            return;
        }
        validatorInstances.beginValidation();
        try {
            validateConstraintInstances(context, leftBean, elementArgument, elementValue, constraints);
        } finally {
            validatorInstances.endValidation();
        }
    }

    @SuppressWarnings("unchecked")
    private <R, E> void validateConstraintInstances(DefaultConstraintValidatorContext<R> context,
                                                    @Nullable Object leftBean,
                                                    Argument<E> elementArgument,
                                                    @Nullable E elementValue,
                                                    List<DefaultConstraintDescriptor<Annotation>> constraints) {
        ConstraintTarget constraintTarget = context.getCurrentPath().getConstraintTarget();
        ValidationPath.DefaultMethodNode executableNode = executableNode(context.getCurrentPath());
        for (DefaultConstraintDescriptor<Annotation> constraint : constraints) {
            context.beginConstraint(constraint, leftBean, elementValue);
            ConstraintTarget validationAppliesTo = constraint.getValidationAppliesTo();
            if (constraint.hasDefinedConstraintValidatorClasses() || validationAppliesTo != null) {
                boolean onExecutable = executableNode != null
                    && (constraintTarget == ConstraintTarget.PARAMETERS || constraintTarget == ConstraintTarget.RETURN_VALUE);
                ConstraintValidatorTargetResolver.checkTargetDeclaration(reflectionSupport,
                    constraint.getType(),
                    constraint.getConstraintValidatorClasses(),
                    validationAppliesTo,
                    onExecutable,
                    executableNode != null && executableNode.getMethodReference().getArguments().length > 0,
                    executableNode instanceof ValidationPath.DefaultConstructorNode
                        || (executableNode != null && executableNode.getMethodReference().getReturnType().getType() != void.class)
                );
            }
            if (validationAppliesTo != null && validationAppliesTo != ConstraintTarget.IMPLICIT && validationAppliesTo != constraintTarget) {
                continue;
            }
            Class<Annotation> constraintType = constraint.getType();
            List<Class<? extends jakarta.validation.ConstraintValidator<Annotation, ?>>> validatorClasses = constraint.getConstraintValidatorClasses();
            ConstraintValidator<Annotation, E> validator = constraint.hasDefinedConstraintValidatorClasses()
                ? (ConstraintValidator<Annotation, E>) constraint.resolvedValidator(validatorInstances, elementArgument.getType(), constraintTarget)
                : null;
            if (validator == null && constraint.hasDefinedConstraintValidatorClasses()) {
                if ((constraintTarget == ConstraintTarget.PARAMETERS || constraintTarget == ConstraintTarget.RETURN_VALUE)
                    && !ConstraintValidatorTargetResolver.supportsTarget(reflectionSupport, validatorClasses, constraintTarget)) {
                    // a constraint declared on an executable whose validators do not validate this phase
                    continue;
                }
                Class<?> validatedBy = ConstraintValidatorTargetResolver.resolve(reflectionSupport, constraintType, validatorClasses, elementArgument.getType(), constraintTarget);
                if (validatedBy != null) {
                    Class<jakarta.validation.ConstraintValidator<Annotation, E>> validatedByConstraint = (Class<jakarta.validation.ConstraintValidator<Annotation, E>>) validatedBy;
                    jakarta.validation.ConstraintValidator<Annotation, E> constraintValidator = validatorInstances.get(validatedByConstraint, elementArgument.getType(), constraintTarget, constraint);
                    if (constraintValidator != null) {
                        validator = constraintValidator instanceof ConstraintValidator<Annotation, E> cv
                            ? cv
                            : new JakartaConstraintValidatorAdapter<>(constraintValidator);
                    }
                }
                if (validator == null && constraint.includesBuiltInValidators()) {
                    // no configured validator handles the type: the built-in ones kept next to them may
                    validator = constraintValidatorRegistry.findConstraintValidator(constraintType, elementArgument.getType()).orElse(null);
                }
                if (validator == null) {
                    if (!validatorClasses.isEmpty()) {
                        throw new UnexpectedTypeException("Cannot find a constraint validator for constraint: " + constraintType.getName() + " and type: " + elementArgument.getType());
                    }
                    continue;
                }
                // resolved once for the type and the target: the resolution, the instance lookup and the adapter
                constraint.resolvedValidator(validatorInstances, elementArgument.getType(), constraintTarget, validator);
            } else if (validator == null) {
                if (constraintTarget == ConstraintTarget.PARAMETERS) {
                    continue;
                }
                validator = constraintValidatorRegistry.findConstraintValidator(constraintType, elementArgument.getType()).orElse(null);
            }
            if (validator == null || validator == ConstraintValidator.VALID) {
                if (validateComposingConstraints(context, leftBean, elementArgument, elementValue, constraint)) {
                    continue;
                }
                throw new UnexpectedTypeException("Cannot find a constraint validator for constraint: " + constraintType.getName() + " and type: " + elementArgument.getType());
            }
            try {
                int reportedBefore = context.reportedViolations();
                if (!validator.isValid(elementValue, constraint.getAnnotationValue(), context)) {
                    if (!context.isDefaultConstraintViolationDisabled()) {
                        context.addViolation(createConstraintViolation(context, leftBean, elementValue, constraint));
                    } else if (context.reportedViolations() == reportedBefore) {
                        // this validator reported nothing, whatever the constraints before it did
                        throw new ValidationException("Default violation is disabled and no violations were added");
                    }
                }
            } catch (ValidationException e) {
                throw e;
            } catch (Exception e) {
                throw new ValidationException("Cannot call 'isValid' on: " + validator.getClass().getName(), e);
            }

            validateComposingConstraints(context, leftBean, elementArgument, elementValue, constraint);
            context.endConstraint();
        }
    }

    private <R, E> boolean validateComposingConstraints(DefaultConstraintValidatorContext<R> context,
                                                        @Nullable Object leftBean,
                                                        Argument<E> elementArgument,
                                                        @Nullable E elementValue,
                                                        DefaultConstraintDescriptor<Annotation> constraint) {
        if (!constraint.hasComposingConstraints()) {
            return false;
        }
        List<DefaultConstraintDescriptor<Annotation>> composingConstraints = List.copyOf(constraint.getComposingConstraintDescriptors());
        if (constraint.isReportAsSingleViolation()) {
            // violations keep their report order: the composing ones, past the size before, give way to the composed one
            Set<ConstraintViolation<R>> violations = context.getOverallViolations();
            int before = violations.size();
            validateConstraints(context, leftBean, elementArgument, elementValue, composingConstraints);
            if (violations.size() > before) {
                Iterator<ConstraintViolation<R>> iterator = violations.iterator();
                for (int i = 0; iterator.hasNext(); i++) {
                    iterator.next();
                    if (i >= before) {
                        iterator.remove();
                    }
                }
                context.addViolation(createConstraintViolation(context, leftBean, elementValue, constraint));
            }
        } else {
            validateConstraints(context, leftBean, elementArgument, elementValue, composingConstraints);
        }
        return true;
    }

    <R> DefaultConstraintViolation<R> createConstraintViolation(DefaultConstraintValidatorContext<R> context,
                                                                @Nullable Object leftBean,
                                                                @Nullable Object elementValue,
                                                                ConstraintDescriptor<Annotation> constraint) {
        final String contextTemplate = context.getMessageTemplate().orElse(null);
        final String messageTemplate = contextTemplate != null ? contextTemplate : constraint.getMessageTemplate();
        final String message;
        try {
            message = messageInterpolator.interpolate(messageTemplate, new DefaultMessageInterpolatorContext(context, constraint, elementValue));
        } catch (ValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ValidationException("Exception during message interpolation", e);
        }

        return new DefaultConstraintViolation<>(
            context.getRootBean(),
            context.getRootClass(),
            leftBean,
            elementValue,
            message,
            messageTemplate,
            new ValidationPath(context.getCurrentPath()),
            constraint,
            context.getExecutableParameterValues(),
            context.getExecutableReturnValue()
        );
    }

    /**
     * The constraints the metadata declares in the groups being validated.
     */
    <R> List<DefaultConstraintDescriptor<Annotation>> getConstraints(DefaultConstraintValidatorContext<R> context,
                                                                     AnnotationMetadata annotationMetadata) {
        List<DefaultConstraintDescriptor<Annotation>> cached = constraintCache.get(annotationMetadata);
        if (cached == null) {
            if (constraintCache.size() > MAX_CACHED_CONSTRAINTS) {
                constraintCache.clear();
            }
            // built outside the map: describing a constraint may look up the constraints of other metadata
            cached = describeConstraints(annotationMetadata);
            List<DefaultConstraintDescriptor<Annotation>> existing = constraintCache.putIfAbsent(annotationMetadata, cached);
            if (existing != null) {
                cached = existing;
            }
        }
        // the list is shared: copied only when the groups validated leave a constraint out
        for (int i = 0; i < cached.size(); i++) {
            if (!context.containsGroup(cached.get(i).getGroups())) {
                List<DefaultConstraintDescriptor<Annotation>> included = new ArrayList<>(cached.size());
                for (DefaultConstraintDescriptor<Annotation> descriptor : cached) {
                    if (context.containsGroup(descriptor.getGroups())) {
                        included.add(descriptor);
                    }
                }
                return included;
            }
        }
        return cached;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<DefaultConstraintDescriptor<Annotation>> describeConstraints(AnnotationMetadata annotationMetadata) {
        List<DefaultConstraintDescriptor<Annotation>> descriptors = new ArrayList<>();
        Set<Class<? extends Annotation>> constraintTypes = ConstraintContainers.constraintTypes(reflectionSupport, annotationMetadata, reflectionSupport.classLoader());
        boolean hasDeclaredConstraint = false;
        for (Class<? extends Annotation> constraintType : constraintTypes) {
            if (ConstraintAnnotationKey.isDeclaredConstraint(annotationMetadata, constraintType)) {
                hasDeclaredConstraint = true;
                break;
            }
        }
        for (Class<? extends Annotation> constraintType : constraintTypes) {
            if (hasDeclaredConstraint && !ConstraintAnnotationKey.isDeclaredConstraint(annotationMetadata, constraintType)) {
                continue;
            }
            Map<String, AnnotationValue<? extends Annotation>> uniqueAnnotationValues = new LinkedHashMap<>();
            for (AnnotationValue<? extends Annotation> annotationValue : ConstraintContainers.values(reflectionSupport, annotationMetadata, constraintType)) {
                uniqueAnnotationValues.putIfAbsent(ConstraintAnnotationKey.of(constraintType, annotationValue), annotationValue);
            }
            for (AnnotationValue<? extends Annotation> annotationValue : uniqueAnnotationValues.values()) {
                declarations.checkConstraintDefinition(constraintType, annotationValue);
                List validatorClasses = validatorOverrides.validatorsOf((Class<Annotation>) constraintType,
                    List.of(annotationValue.classValues(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY)));
                descriptors.add(DefaultConstraintDescriptor.of(reflectionSupport,
                    (Class<Annotation>) constraintType,
                    (AnnotationValue<Annotation>) annotationValue,
                    annotationMetadata,
                    validatorClasses));
            }
        }
        return descriptors;
    }

    /**
     * @return The node of the executable being validated, {@code null} when a bean is
     */
    private static ValidationPath.@Nullable DefaultMethodNode executableNode(ValidationPath path) {
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i) instanceof ValidationPath.DefaultMethodNode methodNode) {
                return methodNode;
            }
        }
        return null;
    }

    /**
     * A Jakarta constraint validator run as a Micronaut one.
     *
     * @param <E> The validated type
     */
    private static final class JakartaConstraintValidatorAdapter<E> implements ConstraintValidator<Annotation, E> {

        private final jakarta.validation.ConstraintValidator<Annotation, E> delegate;

        JakartaConstraintValidatorAdapter(jakarta.validation.ConstraintValidator<Annotation, E> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void initialize(Annotation constraintAnnotation) {
            delegate.initialize(constraintAnnotation);
        }

        @Override
        public boolean isValid(@Nullable E value, AnnotationValue<Annotation> annotationMetadata, ConstraintValidatorContext context) {
            return delegate.isValid(value, context);
        }
    }
}
