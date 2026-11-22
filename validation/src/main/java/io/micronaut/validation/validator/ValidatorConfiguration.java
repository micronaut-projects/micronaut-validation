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

import io.micronaut.context.ExecutionHandleLocator;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.convert.ConversionServiceProvider;
import io.micronaut.validation.validator.constraints.ConstraintValidatorRegistry;
import io.micronaut.validation.validator.constraints.InternalConstraintValidatorFactory;
import io.micronaut.validation.validator.extractors.ValueExtractorRegistry;
import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.TraversableResolver;
import jakarta.validation.executable.ExecutableType;

import java.util.Set;

/**
 * Configuration for the {@link Validator}.
 *
 * @author graemerocher
 * @since 1.2
 */
public interface ValidatorConfiguration extends ConversionServiceProvider {

    /**
     * The prefix to use for config.
     */
    String PREFIX = "micronaut.validator";

    /**
     * Whether the validator is enabled.
     */
    String ENABLED = PREFIX + ".enabled";

    /**
     * The executable types method interception validates by default without the Jakarta Validation modules: every
     * constrained method, getters included.
     *
     * @since 5.3.0
     */
    Set<ExecutableType> MICRONAUT_VALIDATED_EXECUTABLE_TYPES = Set.of(ExecutableType.GETTER_METHODS, ExecutableType.NON_GETTER_METHODS);

    /**
     * @return The constraint registry to use.
     */
    ConstraintValidatorRegistry getConstraintValidatorRegistry();

    /**
     * @return The constraint factory to use.
     * @since 4.3.0
     */
    ConstraintValidatorFactory getConstraintValidatorFactory();

    /**
     * @return The value extractor registry
     */
    ValueExtractorRegistry getValueExtractorRegistry();

    /**
     * @return The clock provider
     */
    ClockProvider getClockProvider();

    /**
     * @return The default clock provider
     */
    ClockProvider getDefaultClockProvider();

    /**
     * @return The traversable resolver to use
     */
    TraversableResolver getTraversableResolver();

    /**
     * @return The default traversable resolver to use
     */
    TraversableResolver getDefaultTraversableResolver();

    /**
     * @return The message interpolator
     */
    MessageInterpolator getMessageInterpolator();

    /**
     * @return The default message interpolator
     */
    MessageInterpolator getDefaultMessageInterpolator();

    /**
     * @return The parameter name provider
     * @since 5.3.0
     */
    default ParameterNameProvider getParameterNameProvider() {
        return getDefaultParameterNameProvider();
    }

    /**
     * @return The default parameter name provider
     * @since 5.3.0
     */
    default ParameterNameProvider getDefaultParameterNameProvider() {
        return new DefaultParameterNameProvider();
    }

    /**
     * The execution handler locator to use.
     * @return The locator
     */
    ExecutionHandleLocator getExecutionHandleLocator();

    /**
     * If true, then the path to the property will be automatically added to the error message.
     * <p>
     * Default: true
     *
     * @return prependPropertyPath flag value
     */
    boolean isPrependPropertyPath();

    /**
     * Whether constraint definitions are checked against the Jakarta Validation rules: a constraint declares
     * the {@code message}, {@code groups} and {@code payload} members with the types and defaults the
     * specification sets, and {@code validationAppliesTo} only when it is both generic and cross-parameter.
     * <p>
     * Default: false, a constraint may omit the members it does not need
     *
     * @return Whether constraint definitions are checked
     * @since 5.3.0
     */
    default boolean isStrictConstraintDefinitions() {
        return false;
    }

    /**
     * Whether method interception validates executables at all. With {@code false}, no intercepted method is
     * validated, whatever {@link jakarta.validation.executable.ValidateOnExecution} declares, as the specification
     * has it for {@code BootstrapConfiguration.isExecutableValidationEnabled()}. It does not affect the executable
     * validation API.
     * <p>
     * Default: true
     *
     * @return Whether method interception validates executables
     * @since 5.3.0
     */
    default boolean isExecutableValidationEnabled() {
        return true;
    }

    /**
     * The kinds of executables method interception validates when {@link jakarta.validation.executable.ValidateOnExecution}
     * does not decide for a method: {@link ExecutableType#GETTER_METHODS} for the getters,
     * {@link ExecutableType#NON_GETTER_METHODS} for the other methods. A method a {@code ValidateOnExecution}
     * includes is validated whatever these are.
     * <p>
     * Default: getters and other methods, every constrained method; the specification defaults to constructors and
     * methods that are not getters, which the Jakarta Validation modules configure.
     *
     * @return The executable types validated by default, without {@link ExecutableType#ALL}, {@link ExecutableType#NONE}
     * or {@link ExecutableType#IMPLICIT}
     * @since 5.3.0
     */
    default Set<ExecutableType> getDefaultValidatedExecutableTypes() {
        return MICRONAUT_VALIDATED_EXECUTABLE_TYPES;
    }

    /**
     * The bean introspector.
     * @return The introspector
     */
    default BeanIntrospector getBeanIntrospector() {
        return BeanIntrospector.SHARED;
    }

    /**
     * Internal to the validator: an implementation keeps the default.
     *
     * @return The validators a configuration defines for the constraints
     * @since 5.3.0
     */
    @Internal
    default ConstraintValidatorOverrides getConstraintValidatorOverrides() {
        return ConstraintValidatorOverrides.NONE;
    }

    /**
     * Internal to the validator: an implementation keeps the default.
     *
     * @return The metadata access support selected for this configuration
     * @since 5.3.0
     */
    @Internal
    default ReflectionSupport getReflectionSupport() {
        return ReflectionSupport.get();
    }

    /**
     * Internal to the validator: an implementation keeps the default.
     *
     * @return The constraint validator factory the validator resolves validators through
     * @since 5.3.0
     */
    @Internal
    default InternalConstraintValidatorFactory getInternalConstraintValidatorFactory() {
        return DefaultValidatorConfiguration.toInternalConstraintValidatorFactory(getConstraintValidatorFactory(), getReflectionSupport());
    }
}
