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
import org.jspecify.annotations.Nullable;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.ValidationException;
import jakarta.validation.metadata.ConstraintDescriptor;

import java.util.Objects;

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
    @Nullable Object leafBean,
    @Nullable Object invalidValue,
    String message,
    String messageTemplate,
    Path path,
    ConstraintDescriptor<?> constraintDescriptor,
    Object @Nullable [] executableParameterValues,
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
    public @Nullable T getRootBean() {
        return rootBean;
    }

    @Override
    public @Nullable Class<T> getRootBeanClass() {
        return rootBeanClass;
    }

    @Override
    public @Nullable Object getLeafBean() {
        return leafBean;
    }

    @Override
    public Object @Nullable [] getExecutableParameters() {
        return executableParameterValues;
    }

    @Override
    public @Nullable Object getExecutableReturnValue() {
        return executableReturnValue;
    }

    @Override
    public Path getPropertyPath() {
        return path;
    }

    @Override
    public @Nullable Object getInvalidValue() {
        return invalidValue;
    }

    @Override
    public ConstraintDescriptor<?> getConstraintDescriptor() {
        return constraintDescriptor;
    }

    @Override
    public <U> U unwrap(Class<U> type) {
        if (type != null && type.isInstance(this)) {
            return type.cast(this);
        }
        throw new ValidationException("Unwrapping to " + type + " is unsupported by this implementation");
    }

    /**
     * Two violations are the same when they carry the same message, template, path and constraint for the same
     * instances: the root bean, leaf bean and invalid value compare by identity, as in the reference
     * implementation. Comparing them by value would walk the validated object graph for every violation, and
     * recurse without end on a graph containing itself.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultConstraintViolation<?> that = (DefaultConstraintViolation<?>) o;
        return rootBean == that.rootBean
            && leafBean == that.leafBean
            && invalidValue == that.invalidValue
            && Objects.equals(message, that.message)
            && Objects.equals(messageTemplate, that.messageTemplate)
            && Objects.equals(path, that.path)
            && Objects.equals(constraintDescriptor, that.constraintDescriptor);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(message, messageTemplate, path, constraintDescriptor);
        result = 31 * result + System.identityHashCode(rootBean);
        result = 31 * result + System.identityHashCode(leafBean);
        return 31 * result + System.identityHashCode(invalidValue);
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
