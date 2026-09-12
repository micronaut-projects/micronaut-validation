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
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.inject.ExecutableMethod;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * An {@link ExecutableMethod} over the {@link BeanMethod} of a bean introspection, for the specification API
 * that names a method by its {@link Method}: the arguments and the metadata are the ones the introspection
 * carries, and the method itself is the one the caller gave.
 *
 * @param <T> The declaring type
 * @param <R> The return type
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
final class IntrospectedExecutable<T, R> implements ExecutableMethod<T, R> {

    private final Class<T> declaringType;
    private final BeanMethod<T, R> beanMethod;
    private final Method method;

    IntrospectedExecutable(Class<T> declaringType, BeanMethod<T, R> beanMethod, Method method) {
        this.declaringType = declaringType;
        this.beanMethod = beanMethod;
        this.method = method;
    }

    @Override
    public Class<T> getDeclaringType() {
        return declaringType;
    }

    @Override
    public String getMethodName() {
        return beanMethod.getName();
    }

    @Override
    public Argument<?>[] getArguments() {
        return beanMethod.getArguments();
    }

    @Override
    public Class<?>[] getArgumentTypes() {
        return Argument.toClassArray(beanMethod.getArguments());
    }

    @Override
    public ReturnType<R> getReturnType() {
        return beanMethod.getReturnType();
    }

    @Override
    public Method getTargetMethod() {
        return method;
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return beanMethod.getAnnotationMetadata();
    }

    @Override
    @Nullable
    @SuppressWarnings("NullAway") // a method can return null
    public R invoke(T instance, @Nullable Object... arguments) {
        return beanMethod.invoke(instance, arguments);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IntrospectedExecutable<?, ?> other && method.equals(other.method);
    }

    @Override
    public int hashCode() {
        return method.hashCode();
    }

    @Override
    public String toString() {
        return declaringType.getName() + "." + getMethodName() + Arrays.toString(getArgumentTypes());
    }
}
