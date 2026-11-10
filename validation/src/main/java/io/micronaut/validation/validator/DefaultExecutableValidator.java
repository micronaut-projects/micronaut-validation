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

import io.micronaut.context.ExecutionHandleLocator;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.beans.BeanConstructor;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ArgumentValue;
import io.micronaut.core.type.MutableArgumentValue;
import io.micronaut.core.type.ReturnType;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.MethodReference;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import static io.micronaut.validation.validator.ValidationChecks.requireNonNull;

/**
 * The validation of the parameters and return values of methods and constructors, the reactive ones included.
 *
 * @since 5.3.0
 */
@Internal
final class DefaultExecutableValidator {

    private final DefaultValidator validator;
    private final ReflectionSupport reflectionSupport;
    private final BeanIntrospector beanIntrospector;
    private final ExecutionHandleLocator executionHandleLocator;
    private final ConversionService conversionService;
    private final ValidatorDeclarations declarations;
    private final ValidationTraversal traversal;
    private final ConstraintEvaluator constraints;

    DefaultExecutableValidator(DefaultValidator validator,
                               ReflectionSupport reflectionSupport,
                               ExecutionHandleLocator executionHandleLocator,
                               ConversionService conversionService,
                               ValidatorDeclarations declarations,
                               ValidationTraversal traversal,
                               ConstraintEvaluator constraints) {
        this.validator = validator;
        this.reflectionSupport = reflectionSupport;
        this.beanIntrospector = reflectionSupport.introspector();
        this.executionHandleLocator = executionHandleLocator;
        this.conversionService = conversionService;
        this.declarations = declarations;
        this.traversal = traversal;
        this.constraints = constraints;
    }

    <T> Set<ConstraintViolation<T>> validateParameters(T object, ExecutableMethod method, Object[] parameterValues, @Nullable BeanValidationContext validationContext) {
        requireNonNull("parameterValues", parameterValues);
        requireNonNull("object", object);
        requireNonNull("method", method);
        final BeanValidationContext beanValidationContext = requireNonNull("context", validationContext);
        final ValidatorDeclarations.ExecutablePlan plan = parametersPlan(method);
        final Argument<?>[] arguments = plan.configured().arguments();
        if (arguments.length != parameterValues.length) {
            throw new IllegalArgumentException("The method parameter array must have exactly " + arguments.length + " elements.");
        }
        return validateMethodParameters(object, method, plan, parameterValues, beanValidationContext);
    }

    @SuppressWarnings("unchecked")
    <T> Set<ConstraintViolation<T>> validateParameters(T object, ExecutableMethod method, Collection<MutableArgumentValue<?>> argumentValues, Class<?>... groups) {
        requireNonNull("object", object);
        requireNonNull("method", method);
        requireNonNull("parameterValues", argumentValues);
        requireNonNull("groups", groups);

        final ValidatorDeclarations.ExecutablePlan plan = parametersPlan(method);
        final Argument<?>[] arguments = plan.configured().arguments();
        if (arguments.length != argumentValues.size()) {
            throw new IllegalArgumentException("The method parameter array must have exactly " + arguments.length + " elements.");
        }

        Object[] parameters = argumentValues.stream().map(ArgumentValue::getValue).toArray();
        Set<ConstraintViolation<T>> violations = validateMethodParameters(object, method, plan, parameters, BeanValidationContext.fromGroups(groups));
        // a publisher or a completion stage is validated as it delivers: the argument becomes the instrumented one
        int index = 0;
        for (MutableArgumentValue<?> argumentValue : argumentValues) {
            Object parameter = parameters[index++];
            if (parameter != argumentValue.getValue()) {
                ((MutableArgumentValue<Object>) argumentValue).setValue(parameter);
            }
        }
        return violations;
    }

    private ValidatorDeclarations.ExecutablePlan parametersPlan(ExecutableMethod<?, ?> method) {
        final ValidatorDeclarations.ExecutablePlan plan = declarations.executablePlan(method);
        declarations.checkParameterDeclarations(plan);
        return plan;
    }

    private <T> Set<ConstraintViolation<T>> validateMethodParameters(T object,
                                                                    ExecutableMethod<?, ?> method,
                                                                    ValidatorDeclarations.ExecutablePlan plan,
                                                                    Object[] parameters,
                                                                    BeanValidationContext validationContext) {
        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(validator, null, object, validationContext);
        try (DefaultConstraintValidatorContext.ValidationCloseable ignored1 = context.withExecutableParameterValues(parameters);
             ValidationPath.ContextualPath ignored = context.getCurrentPath().addMethodNode(method)) {
            validateParametersInternal(context, object, plan.crossParameterArgument(), parameters, plan.configured().arguments(), getParameterNames(method, plan));
        }
        return Collections.unmodifiableSet(context.getOverallViolations());
    }

    /**
     * The method as the runtime type of the object declares it: a method of a super type names every
     * declaration overriding it, so the constraints the implementation adds apply as well, see
     * Jakarta Validation 5.6.5. Without a description of the runtime type, the method is described alone.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <T> ExecutableMethod<T, Object> executableMethod(@Nullable Object object, Method method) {
        Class<?> declaringType = method.getDeclaringClass();
        if (object != null && object.getClass() != declaringType && declaringType.isInstance(object)) {
            Class<Object> receiverType = (Class<Object>) object.getClass();
            Class<?>[] parameterTypes = method.getParameterTypes();
            BeanIntrospection<Object> introspection = validator.getBeanIntrospection(receiverType);
            if (introspection != null) {
                for (BeanMethod<Object, Object> beanMethod : introspection.getBeanMethods()) {
                    if (beanMethod.getName().equals(method.getName())
                        && Arrays.equals(Argument.toClassArray(beanMethod.getArguments()), parameterTypes)
                        && beanMethod.getDeclaringType() != declaringType) {
                        return (ExecutableMethod) new IntrospectedExecutable<>((Class) beanMethod.getDeclaringType(), beanMethod, method);
                    }
                }
            }
        }
        return reflectionSupport.executableMethod(executionHandleLocator, beanIntrospector, method);
    }

    @SuppressWarnings("unchecked")
    <T> Set<ConstraintViolation<T>> validateReturnValue(T bean, ExecutableMethod<?, Object> executableMethod, @Nullable Object returnValue, @Nullable BeanValidationContext validationContext) {
        requireNonNull("object", bean);
        requireNonNull("method", executableMethod);
        final ValidatorDeclarations.ExecutablePlan plan = declarations.executablePlan(executableMethod);
        declarations.checkReturnValueDeclarations(plan);
        final Argument<Object> returnArgument = (Argument<Object>) plan.configured().returnArgument();
        final DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(validator, null, bean, requireNonNull("validationContext", validationContext));

        try (DefaultConstraintValidatorContext.ValidationCloseable ignored1 = context.withExecutableReturnValue(returnValue);
             ValidationPath.ContextualPath ignored2 = context.getCurrentPath().addMethodNode(executableMethod);
             ValidationPath.ContextualPath ignored3 = context.getCurrentPath().addReturnValueNode()) {
            context.validateInGroups(context.findGroupSequences(bean), canCascade ->
                traversal.visitElement(context, bean, returnArgument, returnArgument.getAnnotationMetadata(), returnValue, canCascade, ValidationTraversal.Cascade.UNCHECKED));
        }
        return Collections.unmodifiableSet(context.getOverallViolations());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <T> Set<ConstraintViolation<T>> validateConstructorParameters(Constructor<? extends T> constructor, Object[] parameterValues, Class<?>... groups) {
        requireNonNull("constructor", constructor);
        requireNonNull("groups", groups);

        final Class<? extends T> declaringClass = constructor.getDeclaringClass();
        final BeanIntrospection<? extends T> introspection = validator.getBeanIntrospection(declaringClass);
        final BeanConstructor<? extends T> beanConstructor = reflectionSupport.beanConstructor((BeanIntrospection) introspection, (Constructor) constructor);
        return validateConstructorParameters(
            declaringClass,
            introspection,
            beanConstructor.getAnnotationMetadata(),
            beanConstructor.getArguments(),
            parameterValues,
            BeanValidationContext.fromGroups(groups),
            getParameterNames(constructor)
        );
    }

    <T> Set<ConstraintViolation<T>> validateConstructorParameters(BeanIntrospection<? extends T> introspection, Object[] parameterValues, Class<?>... groups) {
        requireNonNull("introspection", introspection);
        requireNonNull("groups", groups);

        // the constructor itself carries its cross-parameter constraints
        final BeanConstructor<? extends T> constructor = introspection.getConstructor();
        return validateConstructorParameters(
            introspection.getBeanType(),
            introspection,
            constructor.getAnnotationMetadata(),
            constructor.getArguments(),
            parameterValues,
            BeanValidationContext.fromGroups(groups),
            null
        );
    }

    /**
     * Validates the parameters of a constructor: the constraints of each parameter, its cascade, and the
     * cross-parameter constraints of the constructor. The root bean is {@code null}, the constructor having not
     * run; the root bean class is the declaring type.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <T> Set<ConstraintViolation<T>> validateConstructorParameters(Class<? extends T> beanType,
                                                                  @Nullable BeanIntrospection<? extends T> introspection,
                                                                  AnnotationMetadata constructorMetadata,
                                                                  Argument<?>[] constructorArguments,
                                                                  Object @Nullable [] parameterValues,
                                                                  BeanValidationContext validationContext,
                                                                  @Nullable List<String> parameterNames) {
        parameterValues = parameterValues != null ? parameterValues : ArrayUtils.EMPTY_OBJECT_ARRAY;
        final int argLength = constructorArguments.length;
        if (parameterValues.length != argLength) {
            throw new IllegalArgumentException("Expected exactly [" + argLength + "] constructor arguments");
        }
        for (Argument<?> constructorArgument : constructorArguments) {
            ExecutableHierarchy.checkGroupConversions(reflectionSupport, constructorArgument);
        }
        DefaultConstraintValidatorContext<T> context = introspection == null
            ? (DefaultConstraintValidatorContext<T>) DefaultConstraintValidatorContext.forConstructor(validator, (Class<T>) beanType, validationContext)
            : (DefaultConstraintValidatorContext<T>) new DefaultConstraintValidatorContext(validator, introspection, null, validationContext);
        ValidatorDeclarations.ConfiguredExecutable configured = declarations.configuredConstructor(beanType, constructorMetadata, constructorArguments);
        try (DefaultConstraintValidatorContext.ValidationCloseable ignored1 = context.withExecutableParameterValues(parameterValues);
             ValidationPath.ContextualPath ignored = context.getCurrentPath().addConstructorNode(ValidationPath.simpleName(beanType), beanType, constructorArguments)) {
            validateParametersInternal(context, null, declarations.crossParameterArgument(configured.annotationMetadata()), parameterValues, configured.arguments(), parameterNames);
        }
        return Collections.unmodifiableSet(context.getOverallViolations());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <T> Set<ConstraintViolation<T>> validateConstructorReturnValue(Constructor<? extends T> constructor, T createdObject, Class<?>... groups) {
        requireNonNull("constructor", constructor);
        requireNonNull("createdObject", createdObject);
        requireNonNull("groups", groups);
        final Class<? extends T> declaringClass = constructor.getDeclaringClass();
        final BeanIntrospection<? extends T> introspection = validator.getBeanIntrospection(declaringClass);
        final BeanConstructor<? extends T> beanConstructor = reflectionSupport.beanConstructor((BeanIntrospection) introspection, (Constructor) constructor);
        // the constraints of a constructor apply to the object it creates: the root bean is null, like for its parameters
        final DefaultConstraintValidatorContext<T> context = introspection == null
            ? (DefaultConstraintValidatorContext<T>) DefaultConstraintValidatorContext.forConstructor(validator, (Class<T>) declaringClass, BeanValidationContext.fromGroups(groups))
            : (DefaultConstraintValidatorContext<T>) new DefaultConstraintValidatorContext(validator, introspection, null, BeanValidationContext.fromGroups(groups));
        final ValidatorDeclarations.ConfiguredExecutable configured = declarations.configuredConstructor(declaringClass, beanConstructor.getAnnotationMetadata(), beanConstructor.getArguments());
        final AnnotationMetadata constructorMetadata = configured.annotationMetadata();
        ExecutableHierarchy.checkGroupConversions(reflectionSupport, constructorMetadata, constructorMetadata.hasStereotype(Valid.class));
        final Argument<T> returnArgument = (Argument<T>) configured.returnArgument();
        try (DefaultConstraintValidatorContext.ValidationCloseable ignored1 = context.withExecutableReturnValue(createdObject);
             ValidationPath.ContextualPath ignored2 = context.getCurrentPath().addConstructorNode(ValidationPath.simpleName(declaringClass), declaringClass, beanConstructor.getArguments());
             ValidationPath.ContextualPath ignored3 = context.getCurrentPath().addReturnValueNode()) {
            context.validateInGroups(context.findGroupSequences(createdObject), canCascade ->
                traversal.visitElement(context, createdObject, returnArgument, constructorMetadata, createdObject, canCascade, ValidationTraversal.Cascade.UNCHECKED));
        }
        return Collections.unmodifiableSet(context.getOverallViolations());
    }

    <T> Publisher<Object> validateReturnedPublisher(T bean, ExecutableMethod<?, Object> executableMethod, Publisher<Object> publisher, Class<?>... groups) {
        requireNonNull("publisher", publisher);
        final Argument<?> returnArgument = declarations.executablePlan(executableMethod).configured().returnArgument();
        return ReactiveValidation.validateReturnedPublisher(validator, conversionService, bean, executableMethod, returnArgument, publisher, groups);
    }

    <T> CompletionStage<Object> validateReturnedCompletionStage(T bean, ExecutableMethod<?, Object> executableMethod, CompletionStage<Object> completionStage, Class<?>... groups) {
        requireNonNull("completionStage", completionStage);
        final Argument<?> returnArgument = declarations.executablePlan(executableMethod).configured().returnArgument();
        return ReactiveValidation.validateReturnedCompletionStage(validator, bean, executableMethod, returnArgument, completionStage, groups);
    }

    @SuppressWarnings("unchecked")
    <T> Set<ConstraintViolation<T>> validateReturnedContainer(T bean, ExecutableMethod<?, Object> executableMethod, @Nullable Object container, Class<?>... groups) {
        final Argument<Object> returnArgument = (Argument<Object>) declarations.executablePlan(executableMethod).configured().returnArgument();
        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(validator, null, bean, BeanValidationContext.fromGroups(groups));
        try (DefaultConstraintValidatorContext.ValidationCloseable ignored1 = context.withExecutableReturnValue(container);
             ValidationPath.ContextualPath ignored2 = context.getCurrentPath().addMethodNode(executableMethod);
             ValidationPath.ContextualPath ignored3 = context.getCurrentPath().addReturnValueNode()) {
            context.validateInGroups(context.findGroupSequences(bean), canCascade ->
                constraints.validateConstraints(context, bean, returnArgument, container));
        }
        return Collections.unmodifiableSet(context.getOverallViolations());
    }

    <T> Publisher<T> validatePublisher(ReturnType<?> returnType, Publisher<T> publisher, Class<?>... groups) {
        requireNonNull("publisher", publisher);
        requireNonNull("returnType", returnType);
        requireNonNull("groups", groups);
        return ReactiveValidation.validatePublisher(validator, conversionService, returnType, publisher, groups);
    }

    <T> CompletionStage<T> validateCompletionStage(CompletionStage<T> completionStage, Argument<T> argument, Class<?>... groups) {
        requireNonNull("completionStage", completionStage);
        requireNonNull("groups", groups);

        // the groups are active only while the value is validated, so the whole sequence runs once it completes
        return completionStage.thenApply(value -> {
            DefaultConstraintValidatorContext<Object> context = new DefaultConstraintValidatorContext<>(validator, null, null, BeanValidationContext.fromGroups(groups));
            context.validateInGroups(context.findGroupSequences(), canCascade ->
                ReactiveValidation.validateCompletedValue(validator, context, argument, value, true));
            if (!context.getOverallViolations().isEmpty()) {
                throw validator.constraintViolationException(context.getOverallViolations());
            }
            return value;
        });
    }

    @SuppressWarnings("unchecked")
    private <T> void validateParametersInternal(DefaultConstraintValidatorContext<T> context,
                                                @Nullable T bean,
                                                @Nullable Argument<Object[]> crossParameterArgument,
                                                Object[] parameters,
                                                Argument<?>[] arguments,
                                                @Nullable List<String> parameterNames) {
        context.validateInGroups(context.findGroupSequences(bean), canCascade -> {
            if (crossParameterArgument != null) {
                try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addCrossParameterNode()) {
                    constraints.validateConstraints(context, bean, crossParameterArgument, parameters);
                }
            }

            for (int parameterIndex = 0; parameterIndex < arguments.length; parameterIndex++) {
                Argument<Object> argument = (Argument<Object>) arguments[parameterIndex];
                if (!ArgumentValidationMetadata.isValidated(reflectionSupport, argument) && !ArgumentValidationMetadata.hasValidatedTypeArgument(reflectionSupport, argument)) {
                    continue;
                }
                try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addParameterNode(parameterName(argument, parameterNames, parameterIndex), parameterIndex);
                     DefaultConstraintValidatorContext.ValidationCloseable ignore = context.convertGroups(argument.getAnnotationMetadata())) {
                    final Class<Object> parameterType = argument.getType();
                    final Object parameterValue = parameters[parameterIndex];
                    final boolean hasValue = parameterValue != null;
                    final boolean isPublisher = hasValue && Publishers.isConvertibleToPublisher(parameterType);
                    final boolean isCompletionStage = hasValue && CompletionStage.class.isAssignableFrom(parameterType);
                    if (isPublisher || isCompletionStage) {
                        // the constraints of the container itself apply now, the values it holds as they arrive
                        constraints.validateConstraints(context, bean, argument, parameterValue);
                    }
                    if (isPublisher) {
                        ReactiveValidation.instrumentPublisherArgumentWithValidation(validator, context, parameters, parameterIndex, argument, parameterValue, canCascade);
                    } else if (isCompletionStage) {
                        ReactiveValidation.instrumentCompletionStageArgumentWithValidation(validator, context, parameters, parameterIndex, argument, parameterValue, canCascade);
                    } else {
                        traversal.visitElement(context, bean, argument, argument.getAnnotationMetadata(), parameterValue, canCascade, ValidationTraversal.Cascade.UNCHECKED);
                    }
                }
            }
        });
    }

    private @Nullable List<String> getParameterNames(MethodReference<?, ?> method) {
        if (declarations.parameterNameProvider() == null) {
            return null;
        }
        return method instanceof ExecutableMethod<?, ?> executableMethod
            ? getParameterNames(method, declarations.executablePlan(executableMethod))
            : getParameterNames(method, null);
    }

    private @Nullable List<String> getParameterNames(MethodReference<?, ?> method, ValidatorDeclarations.@Nullable ExecutablePlan plan) {
        ParameterNameProvider parameterNameProvider = declarations.parameterNameProvider();
        if (parameterNameProvider == null) {
            return null;
        }
        // the Java method is resolved once per method: no Java method without the reflection module, the names
        // compiled into the metadata then stand
        Method targetMethod = plan == null ? targetMethod(method) : plan.targetMethod(() -> targetMethod(method));
        return targetMethod == null ? null : parameterNameProvider.getParameterNames(targetMethod);
    }

    private @Nullable Method targetMethod(MethodReference<?, ?> method) {
        try {
            return reflectionSupport.targetMethod(method);
        } catch (ValidationException e) {
            return null;
        }
    }

    private @Nullable List<String> getParameterNames(Constructor<?> constructor) {
        ParameterNameProvider parameterNameProvider = declarations.parameterNameProvider();
        if (parameterNameProvider == null) {
            return null;
        }
        return parameterNameProvider.getParameterNames(constructor);
    }

    String parameterName(MethodReference<?, ?> method, int index) {
        return parameterName(method.getArguments()[index], getParameterNames(method), index);
    }

    private static String parameterName(Argument<?> argument, @Nullable List<String> parameterNames, int index) {
        if (parameterNames != null && parameterNames.size() > index) {
            return parameterNames.get(index);
        }
        return argument.getName();
    }
}
