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
package io.micronaut.validation.xml;

import io.micronaut.core.annotation.Internal;
import io.micronaut.validation.bootstrap.MappingMetadataFactory;
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;

import java.io.InputStream;
import java.util.Set;

/**
 * Service adapter for XML mapping metadata.
 *
 * @since 5.3.0
 */
@Internal
public final class XmlMappingMetadataFactory implements MappingMetadataFactory {
    /** Constructor for service discovery. */
    public XmlMappingMetadataFactory() { }

    @Override
    public ValidationMetadataProvider create(ClassLoader classLoader, Set<InputStream> streams) {
        return new XmlValidationMetadataProvider(classLoader, streams);
    }
}
