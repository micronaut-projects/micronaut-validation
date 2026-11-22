/*
 * Copyright 2017-2020 original authors
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

import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.inject.ExecutableMethod;
import jakarta.validation.ConstraintViolation;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;

import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Interface for reactive bean validation.
 *
 * @author graemerocher
 * @since 1.2
 */
public interface ReactiveValidator {

    /**
     * Validate the given publisher by returning a new Publisher that validates each emitted value. If a
     * constraint violation error occurs a {@link jakarta.validation.ConstraintViolationException} will be thrown.
     *
     * @param returnType The required type of publisher
     * @param publisher The publisher
     * @param groups The groups
     * @param <T> The generic type
     * @return The publisher
     */
    <T> Publisher<T> validatePublisher(ReturnType<?> returnType, Publisher<T> publisher, Class<?>... groups);


    /**
     * Validate the given CompletionStage by returning a new CompletionStage that validates the emitted value. If a
     * constraint violation error occurs a {@link jakarta.validation.ConstraintViolationException} will be thrown.
     *
     * @param completionStage The completion stage
     * @param argument        The completion stage element argument
     * @param groups The groups
     * @param <T> The generic type
     * @return The publisher
     */
    <T> CompletionStage<T> validateCompletionStage(CompletionStage<T> completionStage, Argument<T> argument, Class<?>... groups);

    /**
     * Validates the constraints declared on a reactive container a method returned, apart from the values it
     * emits or completes with, which are validated as they arrive.
     *
     * @param bean The bean the method was invoked on
     * @param executableMethod The method
     * @param container The returned container
     * @param groups The groups
     * @param <T> The bean type
     * @return The violations of the container, none by default
     * @since 5.3.0
     */
    default <T> Set<ConstraintViolation<T>> validateReturnedContainer(T bean,
                                                                     ExecutableMethod<?, Object> executableMethod,
                                                                     @Nullable Object container,
                                                                     Class<?>... groups) {
        return Set.of();
    }

    /**
     * Validates the values a publisher a method returned emits, each as an element of the return value of the
     * method invoked on the bean.
     *
     * @param bean The bean the method was invoked on
     * @param executableMethod The method
     * @param publisher The returned publisher
     * @param groups The groups
     * @param <T> The bean type
     * @return The publisher validating its values, by default {@link #validatePublisher} for the return type
     * @since 5.3.0
     */
    default <T> Publisher<Object> validateReturnedPublisher(T bean,
                                                            ExecutableMethod<?, Object> executableMethod,
                                                            Publisher<Object> publisher,
                                                            Class<?>... groups) {
        return validatePublisher(executableMethod.getReturnType(), publisher, groups);
    }

    /**
     * Validates the value a completion stage a method returned completes with, as the return value of the method
     * invoked on the bean.
     *
     * @param bean The bean the method was invoked on
     * @param executableMethod The method
     * @param completionStage The returned completion stage
     * @param groups The groups
     * @param <T> The bean type
     * @return The completion stage validating its value, by default {@link #validateCompletionStage} for the
     * first type argument of the return type
     * @since 5.3.0
     */
    @SuppressWarnings("unchecked")
    default <T> CompletionStage<Object> validateReturnedCompletionStage(T bean,
                                                                        ExecutableMethod<?, Object> executableMethod,
                                                                        CompletionStage<Object> completionStage,
                                                                        Class<?>... groups) {
        Argument<Object> valueArgument = (Argument<Object>) executableMethod.getReturnType().getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
        return validateCompletionStage(completionStage, valueArgument, groups);
    }
}
