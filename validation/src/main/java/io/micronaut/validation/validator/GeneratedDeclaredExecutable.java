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
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.validation.validator.metadata.ValidationDeclaration;
import jakarta.validation.ValidationException;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Metadata-only adapter for caller-supplied methods that have no generated invocation.
 *
 * @param <T> The declaring type
 */
@Internal
final class GeneratedDeclaredExecutable<T> implements ExecutableMethod<T, Object> {
    private final Method supplied;
    private final ValidationDeclaration declaration;

    GeneratedDeclaredExecutable(Method supplied, ValidationDeclaration declaration) {
        this.supplied = supplied;
        this.declaration = declaration;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<T> getDeclaringType() {
        return (Class<T>) declaration.declaringType();
    }

    @Override
    public String getMethodName() {
        return declaration.name();
    }

    @Override
    public Argument<?>[] getArguments() {
        return declaration.parameters().toArray(Argument<?>[]::new);
    }

    @Override
    public ReturnType<Object> getReturnType() {
        return new ReturnType<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Class<Object> getType() {
                return (Class<Object>) declaration.argument().getType();
            }

            @Override
            public Argument<?>[] getTypeParameters() {
                return declaration.argument().getTypeParameters();
            }

            @Override
            public AnnotationMetadata getAnnotationMetadata() {
                return declaration.argument().getAnnotationMetadata();
            }

            @Override
            @SuppressWarnings("unchecked")
            public Argument<Object> asArgument() {
                return (Argument<Object>) declaration.argument();
            }
        };
    }

    @Override
    public Method getTargetMethod() {
        return supplied;
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return declaration.metadata();
    }

    @Override
    public @Nullable Object invoke(T instance, Object @Nullable ... arguments) {
        throw new ValidationException("No generated invocation for " + declaration.declaringType().getName()
            + "." + declaration.name() + ": add micronaut-validation-reflection to invoke inaccessible methods");
    }
}
