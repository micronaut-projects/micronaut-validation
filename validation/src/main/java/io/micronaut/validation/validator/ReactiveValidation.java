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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.inject.ExecutableMethod;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * The validation of the elements emitted by a reactive return value.
 *
 * @since 5.3.0
 */
@Internal
final class ReactiveValidation {

    private ReactiveValidation() {
    }

    /**
     * Validates the elements emitted by the publisher against the constraints of the first type parameter of the return type.
     *
     * @param validator         The validator
     * @param conversionService The conversion service converting the instrumented publisher back to the return type
     * @param returnType        The return type
     * @param publisher         The publisher
     * @param groups            The groups
     * @param <T>               The element type
     * @return The instrumented publisher
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> Publisher<T> validatePublisher(DefaultValidator validator,
                                              ConversionService conversionService,
                                              ReturnType<?> returnType,
                                              Publisher<T> publisher,
                                              Class<?>... groups) {
        if (returnType.getTypeParameters().length == 0) {
            return publisher;
        }
        Argument<Object> typeParameter = (Argument<Object>) returnType.getTypeParameters()[0];
        Argument<Publisher<T>> publisherArgument = Argument.of((Class<Publisher<T>>) publisher.getClass());

        Publisher<Object> output;
        if (Publishers.isSingle(returnType.getType())) {
            output = Mono.from(publisher).flatMap(value -> {
                Set<? extends ConstraintViolation<?>> violations = validatePublisherValue(validator, publisherArgument, publisher, groups, typeParameter, value, true);
                return violations.isEmpty() ? Mono.just(value) :
                    Mono.error(new ConstraintViolationException(violations));
            });
        } else {
            output = Flux.from(publisher).flatMap(value -> {
                Set<? extends ConstraintViolation<?>> violations = validatePublisherValue(validator, publisherArgument, publisher, groups, typeParameter, value, true);
                return violations.isEmpty() ? Flux.just(value) :
                    Flux.error(new ConstraintViolationException(violations));
            });
        }
        Class<?> returnClass = returnType.getType();
        if (!Publisher.class.isAssignableFrom(returnClass)) {
            return (Publisher<T>) output;
        }
        return Publishers.convertPublisher(conversionService, output, (Class<Publisher>) returnClass);
    }

    /**
     * Validates the elements a method returned a publisher of emits, each as an element of the return value of
     * the method invoked on the bean.
     *
     * @param validator         The validator
     * @param conversionService The conversion service converting the instrumented publisher back to the return type
     * @param bean              The bean the method was invoked on
     * @param method            The method
     * @param returnArgument    The return value as the hierarchy of the method declares it
     * @param publisher         The publisher returned
     * @param groups            The groups
     * @param <B>               The bean type
     * @return The instrumented publisher
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <B> Publisher<Object> validateReturnedPublisher(DefaultValidator validator,
                                                           ConversionService conversionService,
                                                           B bean,
                                                           ExecutableMethod<?, Object> method,
                                                           Argument<?> returnArgument,
                                                           Publisher<Object> publisher,
                                                           Class<?>... groups) {
        if (returnArgument.getTypeParameters().length == 0) {
            return publisher;
        }
        Argument<Object> valueArgument = (Argument<Object>) returnArgument.getTypeParameters()[0];
        Class<?> returnClass = method.getReturnType().getType();
        Publisher<Object> output;
        if (Publishers.isSingle(returnClass)) {
            output = Mono.from(publisher).flatMap(value -> {
                Set<? extends ConstraintViolation<?>> violations = validateReturnedValue(validator, bean, method, publisher, "<publisher element>", ValidationPath.DefaultContainerContext.ofIterableContainer(publisher.getClass()), valueArgument, value, groups);
                return violations.isEmpty() ? Mono.just(value) : Mono.error(new ConstraintViolationException(violations));
            });
        } else {
            output = Flux.from(publisher).flatMap(value -> {
                Set<? extends ConstraintViolation<?>> violations = validateReturnedValue(validator, bean, method, publisher, "<publisher element>", ValidationPath.DefaultContainerContext.ofIterableContainer(publisher.getClass()), valueArgument, value, groups);
                return violations.isEmpty() ? Flux.just(value) : Flux.error(new ConstraintViolationException(violations));
            });
        }
        if (!Publisher.class.isAssignableFrom(returnClass)) {
            return output;
        }
        return Publishers.convertPublisher(conversionService, output, (Class<Publisher>) returnClass);
    }

    /**
     * Validates the value a completion stage a method returned completes with, as an element of the return value
     * of the method invoked on the bean.
     *
     * @param validator       The validator
     * @param bean            The bean the method was invoked on
     * @param method          The method
     * @param returnArgument  The return value as the hierarchy of the method declares it
     * @param completionStage The completion stage returned
     * @param groups          The groups
     * @param <B>             The bean type
     * @return The instrumented completion stage
     */
    @SuppressWarnings("unchecked")
    static <B> CompletionStage<Object> validateReturnedCompletionStage(DefaultValidator validator,
                                                                       B bean,
                                                                       ExecutableMethod<?, Object> method,
                                                                       Argument<?> returnArgument,
                                                                       CompletionStage<Object> completionStage,
                                                                       Class<?>... groups) {
        Argument<Object> valueArgument = (Argument<Object>) returnArgument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
        return completionStage.thenApply(value -> {
            Set<? extends ConstraintViolation<?>> violations = validateReturnedValue(validator, bean, method, completionStage, "<completion stage element>", ValidationPath.DefaultContainerContext.ofContainer(CompletionStage.class), valueArgument, value, groups);
            if (!violations.isEmpty()) {
                throw new ConstraintViolationException(violations);
            }
            return value;
        });
    }

    private static <B, E> Set<? extends ConstraintViolation<?>> validateReturnedValue(DefaultValidator validator,
                                                                                      B bean,
                                                                                      ExecutableMethod<?, Object> method,
                                                                                      Object container,
                                                                                      String nodeName,
                                                                                      ValidationPath.ContainerContext containerContext,
                                                                                      Argument<E> valueArgument,
                                                                                      E value,
                                                                                      Class<?>[] groups) {
        DefaultConstraintValidatorContext<B> context = new DefaultConstraintValidatorContext<>(validator, null, bean, BeanValidationContext.fromGroups(groups));
        try (DefaultConstraintValidatorContext.ValidationCloseable ignored = context.withExecutableReturnValue(container);
             ValidationPath.ContextualPath ignored1 = context.getCurrentPath().addMethodNode(method);
             ValidationPath.ContextualPath ignored2 = context.getCurrentPath().addReturnValueNode();
             ValidationPath.ContextualPath ignored3 = context.getCurrentPath().addContainerElementNode(nodeName, containerContext)) {
            context.validateInGroups(context.findGroupSequences(), firstStep ->
                validator.traversal().visitElement(context, bean, valueArgument, value, true));
        }
        return context.getOverallViolations();
    }

    private static <T, E> Set<? extends ConstraintViolation<?>> validatePublisherValue(DefaultValidator validator,
                                                                                       Argument<T> publisherArgument,
                                                                                       T publisher,
                                                                                       Class<?>[] groups,
                                                                                       Argument<E> valueArgument,
                                                                                       E value,
                                                                                       boolean canCascade
    ) {
        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(validator, null, publisher, BeanValidationContext.fromGroups(groups));
        try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addReturnValueNode()) {
            try (ValidationPath.ContextualPath ignored1 = context.getCurrentPath().addContainerElementNode("<publisher element>",
                ValidationPath.DefaultContainerContext.ofIterableContainer(publisherArgument.getType()))) {
                for (DefaultConstraintValidatorContext.ValidationGroup groupSequence : context.findGroupSequences()) {
                    try (DefaultConstraintValidatorContext.GroupsValidation ignore = context.withGroupSequence(groupSequence)) {
                        validator.traversal().visitElement(context, publisher, valueArgument, value, canCascade);
                    }
                }
            }
        }
        return context.getOverallViolations();
    }

    static <R, E> void instrumentPublisherArgumentWithValidation(DefaultValidator validator,
                                                                 DefaultConstraintValidatorContext<R> context,
                                                                 Object[] argumentValues,
                                                                 int argumentIndex,
                                                                 Argument<E> publisherArgument,
                                                                 E parameterValue,
                                                                 boolean canCascade) {
        final Publisher<?> publisher = Publishers.convertPublisher(validator.conversionService(), parameterValue, Publisher.class);
        // the context of the call, copied for every value: a subscription, or a value of one, never sees the
        // violations or the path another left
        DefaultConstraintValidatorContext<R> callContext = context.copy();

        Publisher<?> objectPublisher;
        if (publisherArgument.isSpecifiedSingle()) {
            objectPublisher = Mono.from(publisher)
                .flatMap(value -> {
                    Set<? extends ConstraintViolation<?>> violations = validatePublishedValue(validator, callContext.copy(), publisherArgument, parameterValue, value, canCascade);
                    return violations.isEmpty() ? Mono.just(value) : Mono.error(new ConstraintViolationException(violations));
                });
        } else {
            objectPublisher = Flux.from(publisher).flatMap(value -> {
                Set<? extends ConstraintViolation<?>> violations = validatePublishedValue(validator, callContext.copy(), publisherArgument, parameterValue, value, canCascade);
                return violations.isEmpty() ? Flux.just(value) : Flux.error(new ConstraintViolationException(violations));
            });
        }
        argumentValues[argumentIndex] = Publishers.convertPublisher(validator.conversionService(), objectPublisher, publisherArgument.getType());
    }

    private static <R, E> Set<? extends ConstraintViolation<?>> validatePublishedValue(DefaultValidator validator,
                                                                                       DefaultConstraintValidatorContext<R> context,
                                                                                       Argument<E> publisherArgument,
                                                                                       E value,
                                                                                       Object publisherInstance,
                                                                                       boolean canCascade) {
        Argument<?>[] typeParameters = publisherArgument.getTypeParameters();

        if (typeParameters.length == 0) {
            // No validation if no parameters
            return Set.of();
        }
        Argument<?> valueArgument = typeParameters[0];

        try (ValidationPath.ContextualPath ignored1 = context.getCurrentPath()
            .addContainerElementNode("<publisher element>", ValidationPath.DefaultContainerContext.ofIterableContainer(value.getClass()))) {
            validator.traversal().visitElement(context, context.getRootBean(), (Argument<Object>) valueArgument, publisherInstance, canCascade);
        }
        return context.getOverallViolations();
    }

    /**
     * Validates the value a completion stage completed with, as its element.
     */
    static <T, E> void validateCompletedValue(DefaultValidator validator,
                                              DefaultConstraintValidatorContext<T> context,
                                              Argument<E> argument,
                                              E value,
                                              boolean canCascade) {
        try (ValidationPath.ContextualPath ignored = context.getCurrentPath()
            .addContainerElementNode("<completion stage element>", ValidationPath.DefaultContainerContext.ofContainer(CompletionStage.class))) {
            validator.traversal().visitElement(context, context.getRootBean(), argument, value, canCascade);
        }
    }

    /**
     * Processes a method argument that is a completion stage. Since the argument cannot be validated
     * at this exact time, the validation is applied to the completion stage.
     */
    static <T, E> void instrumentCompletionStageArgumentWithValidation(DefaultValidator validator,
                                                                       DefaultConstraintValidatorContext<T> context,
                                                                       Object[] argumentValues,
                                                                       int argumentIndex,
                                                                       Argument<E> completionStageArgument,
                                                                       E parameterValue,
                                                                       boolean canCascade) {
        final CompletionStage<Object> completionStage = (CompletionStage<Object>) parameterValue;

        Argument<Object> valueArgument = (Argument<Object>) completionStageArgument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);

        argumentValues[argumentIndex] = instrumentCompletionStage(validator, context.copy(), completionStage, valueArgument, canCascade);
    }

    static <T, E> CompletionStage<E> instrumentCompletionStage(DefaultValidator validator,
                                                               DefaultConstraintValidatorContext<T> context,
                                                               CompletionStage<E> completionStage,
                                                               Argument<E> argument,
                                                               boolean canCascade) {
        return completionStage.thenApply(value -> {

            validateCompletedValue(validator, context, argument, value, canCascade);

            if (!context.getOverallViolations().isEmpty()) {
                throw validator.constraintViolationException(context.getOverallViolations());
            }

            return value;
        });
    }
}
