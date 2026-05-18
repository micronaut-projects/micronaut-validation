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
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.ValueExtractor;
import jakarta.validation.valueextraction.ValueExtractorDeclarationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicronautValidatorConfigurationTest {

    @Test
    void validationServiceLoaderBuildsValidatorFactory() {
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertNotNull(validatorFactory.getValidator());
        }
    }

    @Test
    void bootstrapContextIsRestrictedToValidationAndMicronautInfrastructureBeans() {
        try (ApplicationContext context = MicronautValidatorConfiguration.createBootstrapContext(Map.of())) {
            assertTrue(context.getAllBeanDefinitions()
                .stream()
                .allMatch(definition -> isExpectedBootstrapBean(definition.getBeanType().getName())));
        }
    }

    @Test
    void duplicateProgrammaticValueExtractorsFailAtConfigurationTime() {
        MicronautValidatorConfiguration configuration = new MicronautValidatorConfiguration();
        configuration.addValueExtractor(new BoxValueExtractorOne());

        assertThrows(ValueExtractorDeclarationException.class, () ->
            configuration.addValueExtractor(new BoxValueExtractorTwo()));
    }

    private static boolean isExpectedBootstrapBean(String beanType) {
        return beanType.startsWith("io.micronaut.validation")
            || beanType.startsWith("io.micronaut.inject")
            || beanType.startsWith("io.micronaut.context")
            || beanType.startsWith("io.micronaut.core.convert")
            || beanType.startsWith("io.micronaut.core.io.service");
    }

    private static final class Box<T> {
    }

    private static final class BoxValueExtractorOne implements ValueExtractor<Box<@ExtractedValue ?>> {

        @Override
        public void extractValues(Box<?> originalValue, ValueReceiver receiver) {
        }
    }

    private static final class BoxValueExtractorTwo implements ValueExtractor<Box<@ExtractedValue ?>> {

        @Override
        public void extractValues(Box<?> originalValue, ValueReceiver receiver) {
        }
    }
}
