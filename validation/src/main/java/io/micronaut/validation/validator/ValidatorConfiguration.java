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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.convert.ConversionServiceProvider;
import io.micronaut.validation.validator.constraints.ConstraintValidatorRegistry;
import io.micronaut.validation.validator.extractors.ValueExtractorRegistry;
import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.TraversableResolver;

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
     * @return The constraint registry to use.
     */
    @NonNull
    ConstraintValidatorRegistry getConstraintValidatorRegistry();

    /**
     * @return The constraint factory to use.
     * @since 4.3.0
     */
    @NonNull
    ConstraintValidatorFactory getConstraintValidatorFactory();

    /**
     * @return The value extractor registry
     */
    @NonNull
    ValueExtractorRegistry getValueExtractorRegistry();

    /**
     * @return The clock provider
     */
    @NonNull
    ClockProvider getClockProvider();

    /**
     * @return The default clock provider
     */
    @NonNull
    ClockProvider getDefaultClockProvider();

    /**
     * @return The traversable resolver to use
     */
    @NonNull
    TraversableResolver getTraversableResolver();

    /**
     * @return The default traversable resolver to use
     */
    @NonNull
    TraversableResolver getDefaultTraversableResolver();

    /**
     * @return The message interpolator
     */
    @NonNull
    MessageInterpolator getMessageInterpolator();

    /**
     * @return The default message interpolator
     */
    @NonNull
    MessageInterpolator getDefaultMessageInterpolator();

    /**
     * The execution handler locator to use.
     * @return The locator
     */
    @NonNull
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
     * The bean introspector.
     * @return The introspector
     */
    default BeanIntrospector getBeanIntrospector() {
        return BeanIntrospector.SHARED;
    }
}
