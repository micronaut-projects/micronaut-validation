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
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.beans.BeanIntrospection;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import java.util.Set;

/**
 * Extended version of the {@link Valid} interface for Micronaut's implementation.
 *
 * <p>The {@link #getConstraintsForClass(Class)} method is not supported by the implementation.</p>
 *
 * @author graemerocher
 * @since 1.2
 */
public interface Validator extends jakarta.validation.Validator {

    /**
     * Annotation used to define an object as valid.
     */
    String ANN_VALID = Valid.class.getName();
    /**
     * Annotation used to define a constraint.
     */
    String ANN_CONSTRAINT = Constraint.class.getName();

    /**
     * Overridden variation that returns a {@link ExecutableMethodValidator}.
     *
     * @return The validator
     */
    @Override
    @NonNull ExecutableMethodValidator forExecutables();

    @Override
    @NonNull <T> Set<ConstraintViolation<T>> validate(
        @NonNull T object,
        Class<?>... groups
    );

    /**
     * Validates all constraints on {@code object}.
     *
     * @param object            object to validate
     * @param validationContext The context
     * @param <T>               the type of the object to validate
     * @return constraint violations or an empty set if none
     * @throws IllegalArgumentException if object is {@code null}
     *                                  or if {@code null} is passed to the varargs groups
     * @throws ValidationException      if a non recoverable error happens
     *                                  during the validation process
     */
    @NonNull <T> Set<ConstraintViolation<T>> validate(
        @NonNull T object,
        @NonNull BeanValidationContext validationContext
    );

    /**
     * Validate the given introspection and object.
     *
     * @param introspection The introspection
     * @param object        The object
     * @param groups        The groups
     * @param <T>           The object type
     * @return The constraint violations
     */
    @NonNull <T> Set<ConstraintViolation<T>> validate(
        @NonNull BeanIntrospection<T> introspection,
        @NonNull T object, @Nullable Class<?>... groups);

    /**
     * Validate the given introspection and object.
     *
     * @param introspection The introspection
     * @param object        The object
     * @param context       The context
     * @param <T>           The object type
     * @return The constraint violations
     */
    @NonNull <T> Set<ConstraintViolation<T>> validate(
        @NonNull BeanIntrospection<T> introspection,
        @NonNull T object,
        @NonNull BeanValidationContext context);

    @Override
    @NonNull <T> Set<ConstraintViolation<T>> validateProperty(
        @NonNull T object,
        @NonNull String propertyName,
        Class<?>... groups
    );

    /**
     * Validates all constraints placed on the property of {@code object}
     * named {@code propertyName}.
     *
     * @param object       object to validate
     * @param propertyName property to validate (i.e. field and getter constraints)
     * @param context      The context
     * @param <T>          the type of the object to validate
     * @return constraint violations or an empty set if none
     * @throws IllegalArgumentException if {@code object} is {@code null},
     *                                  if {@code propertyName} is {@code null}, empty or not a valid object property
     *                                  or if {@code null} is passed to the varargs groups
     * @throws ValidationException      if a non recoverable error happens
     *                                  during the validation process
     */
    @NonNull <T> Set<ConstraintViolation<T>> validateProperty(
        @NonNull T object,
        @NonNull String propertyName,
        BeanValidationContext context
    );

    @Override
    @NonNull <T> Set<ConstraintViolation<T>> validateValue(
        @NonNull Class<T> beanType,
        @NonNull String propertyName,
        @Nullable Object value,
        Class<?>... groups
    );

    /**
     * Validates all constraints placed on the property named {@code propertyName}
     * of the class {@code beanType} would the property value be {@code value}.
     * <p>
     * {@link ConstraintViolation} objects return {@code null} for
     * {@link ConstraintViolation#getRootBean()} and
     * {@link ConstraintViolation#getLeafBean()}.
     *
     * @param beanType the bean type
     * @param propertyName property to validate
     * @param value property value to validate
     * @param context The context
     * @param <T> the type of the object to validate
     * @return constraint violations or an empty set if none
     * @throws IllegalArgumentException if {@code beanType} is {@code null},
     *         if {@code propertyName} is {@code null}, empty or not a valid object property
     *         or if {@code null} is passed to the varargs groups
     * @throws ValidationException if a non recoverable error happens
     *         during the validation process
     */
    @NonNull <T> Set<ConstraintViolation<T>> validateValue(
        @NonNull Class<T> beanType,
        @NonNull String propertyName,
        @Nullable Object value,
        BeanValidationContext context
    );

    /**
     * Constructs a new default instance. Note that the returned instance will not contain
     * managed {@link io.micronaut.validation.validator.constraints.ConstraintValidator} instances and using
     * {@link jakarta.inject.Inject} should be preferred.
     *
     * @return The validator.
     */
    static @NonNull Validator getInstance() {
        return new DefaultValidator(
            new DefaultValidatorConfiguration()
        );
    }
}
