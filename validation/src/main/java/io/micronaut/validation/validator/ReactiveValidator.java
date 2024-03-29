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

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import org.reactivestreams.Publisher;

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
    @NonNull <T> Publisher<T> validatePublisher(@NonNull ReturnType<?> returnType, @NonNull Publisher<T> publisher, Class<?>... groups);


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
    @NonNull <T> CompletionStage<T> validateCompletionStage(@NonNull CompletionStage<T> completionStage, @NonNull Argument<T> argument, Class<?>... groups);
}
