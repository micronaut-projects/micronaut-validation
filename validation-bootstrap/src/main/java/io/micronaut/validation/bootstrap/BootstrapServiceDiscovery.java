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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.validation.validator.metadata.MessageInterpolatorProvider;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/** Service registration lookup for optional validation modules. */
@Internal
final class BootstrapServiceDiscovery {
    private BootstrapServiceDiscovery() { }

    static <T> List<T> services(Class<T> type, ClassLoader loader) {
        return ServiceLoader.load(type, loader).stream().map(ServiceLoader.Provider::get).toList();
    }

    static Optional<ConstraintMappingConfigurer> mappingConfigurer(ClassLoader loader) {
        return SoftServiceLoader.load(ConstraintMappingConfigurer.class, loader).firstAvailable();
    }

    static List<MessageInterpolatorProvider> interpolators(ClassLoader loader) {
        return SoftServiceLoader.load(MessageInterpolatorProvider.class, loader).collectAll();
    }
}
