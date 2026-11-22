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

import io.micronaut.context.BeanContext;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Internal;
import io.micronaut.validation.validator.DefaultValidatorConfiguration;
import jakarta.inject.Singleton;

/**
 * Applies the executable validation of the bootstrap configuration to the validator configuration of an application
 * context, as a container integrating Jakarta Validation reads {@code BootstrapConfiguration.isExecutableValidationEnabled()}
 * and {@code getDefaultValidatedExecutableTypes()} when it intercepts methods: what {@code META-INF/validation.xml}
 * declares, read by micronaut-validation-xml, else the defaults of the specification. The configuration is read when
 * a method is first intercepted, and a configuration property set in the application decides over it.
 *
 * @since 5.3.0
 */
@Internal
@Singleton
final class BootstrapExecutableValidation implements BeanCreatedEventListener<DefaultValidatorConfiguration> {

    private final ClassLoader classLoader;

    BootstrapExecutableValidation(BeanContext beanContext) {
        this.classLoader = beanContext.getClassLoader();
    }

    @Override
    public DefaultValidatorConfiguration onCreated(BeanCreatedEvent<DefaultValidatorConfiguration> event) {
        return event.getBean().setBootstrapConfiguration(() -> MicronautValidatorConfiguration.loadBootstrapConfiguration(classLoader));
    }
}
