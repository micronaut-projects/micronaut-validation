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
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.inject.ExecutableMethod;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Keeps a caller-supplied Jakarta signature beside generated executable metadata.
 *
 * @param <T> The declaring bean type
 * @param <R> The return type
 */
final class CallerSuppliedExecutable<T, R> implements ExecutableMethod<T, R> {
    private final ExecutableMethod<T, R> delegate;
    private final Method supplied;

    CallerSuppliedExecutable(ExecutableMethod<T, R> delegate, Method supplied) {
        this.delegate = delegate;
        this.supplied = supplied;
    }

    Method suppliedMethod() {
        return supplied;
    }

    @Override
    public Class<T> getDeclaringType() {
        return delegate.getDeclaringType();
    }

    @Override
    public String getMethodName() {
        return delegate.getMethodName();
    }

    @Override
    public Argument<?>[] getArguments() {
        return delegate.getArguments();
    }

    @Override
    public ReturnType<R> getReturnType() {
        return delegate.getReturnType();
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return delegate.getAnnotationMetadata();
    }

    @Override
    public Method getTargetMethod() {
        return supplied;
    }

    @Override
    public @Nullable R invoke(T instance, @Nullable Object... arguments) {
        return delegate.invoke(instance, arguments);
    }
}
