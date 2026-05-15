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
package io.micronaut.validation.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.validation.validator.DefaultValidatorFactory;
import io.micronaut.validation.validator.Validator;
import io.micronaut.validation.validator.ValidatorConfiguration;

/**
 * Validator factory backed by a private bootstrap {@link ApplicationContext}.
 *
 * @since 5.1
 */
final class BootstrapValidatorFactory extends DefaultValidatorFactory {

    private final ApplicationContext applicationContext;

    BootstrapValidatorFactory(Validator validator,
                              ValidatorConfiguration configuration,
                              ApplicationContext applicationContext) {
        super(validator, configuration);
        this.applicationContext = applicationContext;
    }

    @Override
    protected jakarta.validation.Validator newValidator(ValidatorConfiguration configuration) {
        return MicronautValidatorConfiguration.createValidator(configuration);
    }

    @Override
    public void close() {
        applicationContext.close();
    }
}
