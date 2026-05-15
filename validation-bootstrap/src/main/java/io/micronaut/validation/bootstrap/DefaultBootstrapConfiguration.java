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

import jakarta.validation.BootstrapConfiguration;
import jakarta.validation.executable.ExecutableType;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * Default immutable {@link BootstrapConfiguration}.
 *
 * @param defaultProviderClassName The default provider class name
 * @param constraintValidatorFactoryClassName The constraint validator factory class name
 * @param messageInterpolatorClassName The message interpolator class name
 * @param traversableResolverClassName The traversable resolver class name
 * @param parameterNameProviderClassName The parameter name provider class name
 * @param clockProviderClassName The clock provider class name
 * @param valueExtractorClassNames The value extractor class names
 * @param constraintMappingResourcePaths The constraint mapping resource paths
 * @param executableValidationEnabled Whether executable validation is enabled
 * @param defaultValidatedExecutableTypes The default executable types
 * @param properties The bootstrap properties
 * @since 5.1
 */
public record DefaultBootstrapConfiguration(
    @Nullable String defaultProviderClassName,
    @Nullable String constraintValidatorFactoryClassName,
    @Nullable String messageInterpolatorClassName,
    @Nullable String traversableResolverClassName,
    @Nullable String parameterNameProviderClassName,
    @Nullable String clockProviderClassName,
    Set<String> valueExtractorClassNames,
    Set<String> constraintMappingResourcePaths,
    boolean executableValidationEnabled,
    Set<ExecutableType> defaultValidatedExecutableTypes,
    Map<String, String> properties
) implements BootstrapConfiguration {

    private static final BootstrapConfiguration EMPTY = new DefaultBootstrapConfiguration(
        null,
        null,
        null,
        null,
        null,
        null,
        Set.of(),
        Set.of(),
        true,
        Set.of(ExecutableType.CONSTRUCTORS, ExecutableType.NON_GETTER_METHODS),
        Map.of()
    );

    /**
     * @return Empty bootstrap configuration
     */
    public static BootstrapConfiguration empty() {
        return EMPTY;
    }

    @Override
    public @Nullable String getDefaultProviderClassName() {
        return defaultProviderClassName;
    }

    @Override
    public @Nullable String getConstraintValidatorFactoryClassName() {
        return constraintValidatorFactoryClassName;
    }

    @Override
    public @Nullable String getMessageInterpolatorClassName() {
        return messageInterpolatorClassName;
    }

    @Override
    public @Nullable String getTraversableResolverClassName() {
        return traversableResolverClassName;
    }

    @Override
    public @Nullable String getParameterNameProviderClassName() {
        return parameterNameProviderClassName;
    }

    @Override
    public @Nullable String getClockProviderClassName() {
        return clockProviderClassName;
    }

    @Override
    public Set<String> getValueExtractorClassNames() {
        return valueExtractorClassNames;
    }

    @Override
    public Set<String> getConstraintMappingResourcePaths() {
        return constraintMappingResourcePaths;
    }

    @Override
    public boolean isExecutableValidationEnabled() {
        return executableValidationEnabled;
    }

    @Override
    public Set<ExecutableType> getDefaultValidatedExecutableTypes() {
        return defaultValidatedExecutableTypes;
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }
}
