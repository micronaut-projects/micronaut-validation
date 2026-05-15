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

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.TraversableResolver;
import jakarta.validation.ValidatorContext;
import jakarta.validation.ValidatorFactory;

/**
 * Default validator factory implementation.
 *
 * @author graemerocher
 * @since 1.2.0
 */
@Requires(missingBeans = ValidatorFactory.class)
@Internal
@Singleton
public class DefaultValidatorFactory implements ValidatorFactory {

    private final Validator validator;
    private final ValidatorConfiguration configuration;

    /**
     * The constructor.
     */
    public DefaultValidatorFactory() {
        this(new DefaultValidatorConfiguration());
    }

    /**
     * The constructor.
     * @param configuration The configuration.
     */
    public DefaultValidatorFactory(ValidatorConfiguration configuration) {
        this(new DefaultValidator(configuration), configuration);
    }

    /**
     * Default constructor.
     * @param validator The validator.
     * @param configuration The configuration.
     */
    @Inject
    public DefaultValidatorFactory(Validator validator, ValidatorConfiguration configuration) {
        this.validator = validator;
        this.configuration = configuration;
    }

    @Override
    public jakarta.validation.Validator getValidator() {
        return validator;
    }

    @Override
    public ValidatorContext usingContext() {
        return new DefaultFactoryValidatorContext(newValidatorConfiguration());
    }

    @Override
    public MessageInterpolator getMessageInterpolator() {
        return configuration.getMessageInterpolator();
    }

    @Override
    public TraversableResolver getTraversableResolver() {
        return configuration.getTraversableResolver();
    }

    @Override
    public ConstraintValidatorFactory getConstraintValidatorFactory() {
        return configuration.getConstraintValidatorFactory();
    }

    @Override
    public ParameterNameProvider getParameterNameProvider() {
        return configuration.getParameterNameProvider();
    }

    @Override
    public ClockProvider getClockProvider() {
        return configuration.getClockProvider();
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        throw new UnsupportedOperationException("Method unwrap(..) not supported");
    }

    @Override
    public void close() {
        // no-op
    }

    /**
     * Creates a validator for the given configuration.
     *
     * @param configuration The validator configuration
     * @return The validator
     * @since 5.1
     */
    protected jakarta.validation.Validator newValidator(ValidatorConfiguration configuration) {
        return new DefaultValidator(configuration);
    }

    private DefaultValidatorConfiguration newValidatorConfiguration() {
        DefaultValidatorConfiguration newValidatorConfiguration = new DefaultValidatorConfiguration();
        newValidatorConfiguration.setBeanIntrospector(configuration.getBeanIntrospector());
        newValidatorConfiguration.setMetadataProviders(configuration.getMetadataProviders());
        newValidatorConfiguration.setConstraintValidatorRegistry(configuration.getConstraintValidatorRegistry());
        newValidatorConfiguration.setValueExtractorRegistry(configuration.getValueExtractorRegistry());
        newValidatorConfiguration.setClockProvider(configuration.getClockProvider());
        newValidatorConfiguration.setTraversableResolver(configuration.getTraversableResolver());
        newValidatorConfiguration.setMessageInterpolator(configuration.getMessageInterpolator());
        newValidatorConfiguration.constraintValidatorFactory(configuration.getConstraintValidatorFactory());
        newValidatorConfiguration.setParameterNameProvider(configuration.getParameterNameProvider());
        newValidatorConfiguration.setExecutionHandleLocator(configuration.getExecutionHandleLocator());
        newValidatorConfiguration.setConversionService(configuration.getConversionService());
        newValidatorConfiguration.setPrependPropertyPath(configuration.isPrependPropertyPath());
        return newValidatorConfiguration;
    }

    private final class DefaultFactoryValidatorContext implements ValidatorContext {

        private final DefaultValidatorConfiguration validatorConfiguration;

        private DefaultFactoryValidatorContext(DefaultValidatorConfiguration validatorConfiguration) {
            this.validatorConfiguration = validatorConfiguration;
        }

        @Override
        public ValidatorContext messageInterpolator(MessageInterpolator messageInterpolator) {
            validatorConfiguration.messageInterpolator(messageInterpolator);
            return this;
        }

        @Override
        public ValidatorContext traversableResolver(TraversableResolver traversableResolver) {
            validatorConfiguration.traversableResolver(traversableResolver);
            return this;
        }

        @Override
        public ValidatorContext constraintValidatorFactory(ConstraintValidatorFactory factory) {
            validatorConfiguration.constraintValidatorFactory(factory);
            return this;
        }

        @Override
        public ValidatorContext parameterNameProvider(ParameterNameProvider parameterNameProvider) {
            validatorConfiguration.parameterNameProvider(parameterNameProvider);
            return this;
        }

        @Override
        public ValidatorContext clockProvider(ClockProvider clockProvider) {
            validatorConfiguration.clockProvider(clockProvider);
            return this;
        }

        @Override
        public ValidatorContext addValueExtractor(jakarta.validation.valueextraction.ValueExtractor<?> extractor) {
            validatorConfiguration.addValueExtractor(extractor);
            return this;
        }

        @Override
        public jakarta.validation.Validator getValidator() {
            return newValidator(validatorConfiguration);
        }
    }
}
