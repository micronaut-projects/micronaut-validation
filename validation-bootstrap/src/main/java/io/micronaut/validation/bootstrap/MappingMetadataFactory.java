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
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;
import java.io.InputStream;
import java.util.Set;

/**
 * Factory supplied by the optional XML module.
 *
 * @since 5.3.0
 */
@Internal
public interface MappingMetadataFactory {
    /**
     * Parses mapping streams and transfers their ownership to the provider.
     *
     * @param classLoader The application loader
     * @param streams The owned mapping streams
     * @return The parsed mapping metadata
     */
    ValidationMetadataProvider create(ClassLoader classLoader, Set<InputStream> streams);
}
