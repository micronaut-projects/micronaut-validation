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
import io.micronaut.validation.validator.DefaultValidatorConfiguration;

import java.io.InputStream;
import java.util.Set;

/**
 * Applies constraint mappings to the configuration of a validator, supplied by the optional XML module.
 *
 * @since 5.3.0
 */
@Internal
public interface ConstraintMappingConfigurer {
    /**
     * Parses the mapping streams, which it then owns and closes, and configures the validator with them:
     * the introspector describing the mapped beans and the validators defined for the constraints.
     *
     * @param configuration The validator configuration, with its introspector and metadata access set
     * @param streams       The owned mapping streams
     */
    void configure(DefaultValidatorConfiguration configuration, Set<InputStream> streams);
}
