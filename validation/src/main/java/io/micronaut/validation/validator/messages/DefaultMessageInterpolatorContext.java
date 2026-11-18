/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.validation.validator.messages;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;
import io.micronaut.validation.validator.DefaultConstraintValidatorContext;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ValidationException;
import jakarta.validation.metadata.ConstraintDescriptor;

import java.lang.annotation.Annotation;

/**
 * Default implementation of {@link MessageInterpolator.Context}.
 */
@Internal
public final class DefaultMessageInterpolatorContext implements MessageInterpolator.Context {

    private final DefaultConstraintValidatorContext<?> validatorContext;
    private final ConstraintDescriptor<Annotation> constraintDescriptor;
    @Nullable
    private final Object validatedValue;
    private final boolean customViolation;

    public DefaultMessageInterpolatorContext(
        DefaultConstraintValidatorContext<?> validatorContext,
        ConstraintDescriptor<Annotation> constraintDescriptor,
        @Nullable Object validatedValue
    ) {
        this(validatorContext, constraintDescriptor, validatedValue, false);
    }

    /**
     * @param validatorContext The validator context
     * @param constraintDescriptor The descriptor of the violated constraint
     * @param validatedValue The validated value
     * @param customViolation Whether the template is the one of a violation a constraint validator built
     * @since 5.3.0
     */
    public DefaultMessageInterpolatorContext(
        DefaultConstraintValidatorContext<?> validatorContext,
        ConstraintDescriptor<Annotation> constraintDescriptor,
        @Nullable Object validatedValue,
        boolean customViolation
    ) {
        this.validatorContext = validatorContext;
        this.constraintDescriptor = constraintDescriptor;
        this.validatedValue = validatedValue;
        this.customViolation = customViolation;
    }

    /**
     * @return Whether the template is the one of a violation a constraint validator built with
     * {@link jakarta.validation.ConstraintValidatorContext#buildConstraintViolationWithTemplate(String)}, which
     * may carry what was validated
     * @since 5.3.0
     */
    public boolean isCustomViolation() {
        return customViolation;
    }

    public DefaultConstraintValidatorContext<?> getValidatorContext() {
        return validatorContext;
    }

    @Override
    public ConstraintDescriptor<?> getConstraintDescriptor() {
        return constraintDescriptor;
    }

    @Override
    public @Nullable Object getValidatedValue() {
        return validatedValue;
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        throw new ValidationException("Not supported!");
    }
}
