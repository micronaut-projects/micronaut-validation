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
package io.micronaut.validation.tck.runtime;

import io.micronaut.core.annotation.Internal;
import io.micronaut.validation.validator.DefaultValidatorFactory;
import jakarta.validation.Configuration;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.spi.BootstrapState;
import jakarta.validation.spi.ConfigurationState;
import jakarta.validation.spi.ValidationProvider;

@Internal
public final class TckValidationProvider implements ValidationProvider<TckValidatorConfiguration> {

    @Override
    public TckValidatorConfiguration createSpecializedConfiguration(BootstrapState state) {
        return new TckValidatorConfiguration();
    }

    @Override
    public Configuration<?> createGenericConfiguration(BootstrapState state) {
        throw new RuntimeException();
    }

    @Override
    public ValidatorFactory buildValidatorFactory(ConfigurationState configurationState) {
        return new DefaultValidatorFactory();
    }

}
