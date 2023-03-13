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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;

/**
 * Default implementation of {@link ConstraintViolation}.
 *
 * @param rootBean                  the root bean given for validation
 * @param rootBeanClass             the type of the root bean
 * @param leafBean                  the bean that failed validation
 * @param invalidValue              the value of the leaf bean
 * @param message                   message
 * @param messageTemplate           the template used for message
 * @param path                      the path to the leaf bean
 * @param constraintDescriptor      the descriptor of constraint for which validation failed
 * @param executableParameterValues the arguments provided to method if executable was validated
 * @param executableReturnValue     the arguments provided to method if executable return was validated
 * @param <T>                       The bean type.
 */
@Internal
record DefaultConstraintViolation<T>(
    @Nullable T rootBean,
    @Nullable Class<T> rootBeanClass,
    Object leafBean,
    Object invalidValue,
    String message,
    String messageTemplate,
    Path path,
    ConstraintDescriptor<?> constraintDescriptor,
    @Nullable Object[] executableParameterValues,
    @Nullable Object executableReturnValue
) implements ConstraintViolation<T> {

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String getMessageTemplate() {
        return messageTemplate;
    }

    @Override
    public T getRootBean() {
        return rootBean;
    }

    @Override
    public Class<T> getRootBeanClass() {
        return rootBeanClass;
    }

    @Override
    public Object getLeafBean() {
        return leafBean;
    }

    @Override
    public Object[] getExecutableParameters() {
        return executableParameterValues;
    }

    @Override
    public Object getExecutableReturnValue() {
        return executableReturnValue;
    }

    @Override
    public Path getPropertyPath() {
        return path;
    }

    @Override
    public Object getInvalidValue() {
        return invalidValue;
    }

    @Override
    public ConstraintDescriptor<?> getConstraintDescriptor() {
        return constraintDescriptor;
    }

    @Override
    public <U> U unwrap(Class<U> type) {
        throw new UnsupportedOperationException("Unwrapping is unsupported by this implementation");
    }

    @Override
    public String toString() {
        return "DefaultConstraintViolation{" +
            "rootBean=" + rootBeanClass +
            ", invalidValue=" + invalidValue +
            ", path=" + path +
            '}';
    }
}
