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
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.validation.validator.DefaultValidator;
import io.micronaut.validation.validator.DefaultValidatorConfiguration;
import io.micronaut.validation.validator.Validator;
import io.micronaut.validation.validator.ValidatorConfiguration;
import jakarta.validation.BootstrapConfiguration;
import jakarta.validation.ClockProvider;
import jakarta.validation.Configuration;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.TraversableResolver;
import jakarta.validation.ValidationException;
import jakarta.validation.ValidationProviderResolver;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.spi.BootstrapState;
import jakarta.validation.spi.ConfigurationState;
import jakarta.validation.valueextraction.ValueExtractor;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Jakarta Validation {@link Configuration} implementation.
 *
 * @since 5.1
 */
public final class MicronautValidatorConfiguration implements Configuration<MicronautValidatorConfiguration>, ConfigurationState {

    private static final String BOOTSTRAP_PROPERTY_SOURCE = "micronaut-validation-bootstrap";
    private static final Set<String> BOOTSTRAP_PACKAGES = Set.of(
        "io.micronaut.validation",
        "io.micronaut.inject",
        "io.micronaut.context",
        "io.micronaut.core.convert",
        "io.micronaut.core.io.service"
    );

    private final DefaultValidatorConfiguration defaults = new DefaultValidatorConfiguration();
    private final Set<InputStream> mappingStreams = new LinkedHashSet<>();
    private final Set<ValueExtractor<?>> valueExtractors = new LinkedHashSet<>();
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final ClassLoader classLoader;
    @Nullable
    private final BootstrapState bootstrapState;
    private final boolean honorXmlDefaultProvider;
    private BootstrapConfiguration bootstrapConfiguration = DefaultBootstrapConfiguration.empty();

    private boolean ignoreXmlConfiguration;
    @Nullable
    private MessageInterpolator messageInterpolator;
    @Nullable
    private TraversableResolver traversableResolver;
    @Nullable
    private ConstraintValidatorFactory constraintValidatorFactory;
    @Nullable
    private ParameterNameProvider parameterNameProvider;
    @Nullable
    private ClockProvider clockProvider;
    private boolean messageInterpolatorConfigured;
    private boolean traversableResolverConfigured;
    private boolean constraintValidatorFactoryConfigured;
    private boolean parameterNameProviderConfigured;
    private boolean clockProviderConfigured;

    /**
     * Creates a configuration.
     */
    public MicronautValidatorConfiguration() {
        this(null, true);
    }

    MicronautValidatorConfiguration(@Nullable BootstrapState bootstrapState, boolean honorXmlDefaultProvider) {
        this.bootstrapState = bootstrapState;
        this.honorXmlDefaultProvider = honorXmlDefaultProvider;
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = MicronautValidatorConfiguration.class.getClassLoader();
        }
        this.classLoader = classLoader;
        bootstrapConfiguration = ServiceLoader.load(BootstrapConfigurationLoader.class, classLoader)
            .stream()
            .map(ServiceLoader.Provider::get)
            .map(loader -> loader.load(this.classLoader))
            .flatMap(Optional::stream)
            .findFirst()
            .orElseGet(DefaultBootstrapConfiguration::empty);
    }

    @Override
    public MicronautValidatorConfiguration ignoreXmlConfiguration() {
        ignoreXmlConfiguration = true;
        return this;
    }

    @Override
    public MicronautValidatorConfiguration messageInterpolator(MessageInterpolator interpolator) {
        messageInterpolator = interpolator;
        messageInterpolatorConfigured = true;
        return this;
    }

    @Override
    public MicronautValidatorConfiguration traversableResolver(TraversableResolver resolver) {
        traversableResolver = resolver;
        traversableResolverConfigured = true;
        return this;
    }

    @Override
    public MicronautValidatorConfiguration constraintValidatorFactory(ConstraintValidatorFactory constraintValidatorFactory) {
        this.constraintValidatorFactory = constraintValidatorFactory;
        constraintValidatorFactoryConfigured = true;
        return this;
    }

    @Override
    public MicronautValidatorConfiguration parameterNameProvider(ParameterNameProvider parameterNameProvider) {
        this.parameterNameProvider = parameterNameProvider;
        parameterNameProviderConfigured = true;
        return this;
    }

    @Override
    public MicronautValidatorConfiguration clockProvider(ClockProvider clockProvider) {
        this.clockProvider = clockProvider;
        clockProviderConfigured = true;
        return this;
    }

    @Override
    public MicronautValidatorConfiguration addValueExtractor(ValueExtractor<?> extractor) {
        valueExtractors.add(extractor);
        return this;
    }

    @Override
    public MicronautValidatorConfiguration addMapping(InputStream stream) {
        mappingStreams.add(stream);
        return this;
    }

    @Override
    public MicronautValidatorConfiguration addProperty(String name, String value) {
        properties.put(name, value);
        return this;
    }

    @Override
    public MessageInterpolator getDefaultMessageInterpolator() {
        return defaults.getDefaultMessageInterpolator();
    }

    @Override
    public TraversableResolver getDefaultTraversableResolver() {
        return defaults.getDefaultTraversableResolver();
    }

    @Override
    public ConstraintValidatorFactory getDefaultConstraintValidatorFactory() {
        return defaults.getConstraintValidatorFactory();
    }

    @Override
    public ParameterNameProvider getDefaultParameterNameProvider() {
        return defaults.getDefaultParameterNameProvider();
    }

    @Override
    public ClockProvider getDefaultClockProvider() {
        return defaults.getDefaultClockProvider();
    }

    @Override
    public BootstrapConfiguration getBootstrapConfiguration() {
        return bootstrapConfiguration;
    }

    @Override
    public ValidatorFactory buildValidatorFactory() {
        if (honorXmlDefaultProvider && !ignoreXmlConfiguration) {
            Optional<ValidatorFactory> defaultProviderFactory = buildXmlDefaultProviderFactory();
            if (defaultProviderFactory.isPresent()) {
                return defaultProviderFactory.get();
            }
        }
        return buildValidatorFactoryInternal(this);
    }

    @Override
    public boolean isIgnoreXmlConfiguration() {
        return ignoreXmlConfiguration;
    }

    @Override
    public MessageInterpolator getMessageInterpolator() {
        if (messageInterpolator != null) {
            return messageInterpolator;
        }
        if (!ignoreXmlConfiguration && bootstrapConfiguration.getMessageInterpolatorClassName() != null) {
            return instantiate(bootstrapConfiguration.getMessageInterpolatorClassName(), MessageInterpolator.class);
        }
        return getDefaultMessageInterpolator();
    }

    @Override
    public Set<InputStream> getMappingStreams() {
        Set<InputStream> streams = new LinkedHashSet<>(mappingStreams);
        if (!ignoreXmlConfiguration) {
            for (String mappingPath : bootstrapConfiguration.getConstraintMappingResourcePaths()) {
                InputStream inputStream = classLoader.getResourceAsStream(mappingPath);
                if (inputStream != null) {
                    streams.add(inputStream);
                }
            }
        }
        return Set.copyOf(streams);
    }

    @Override
    public Set<ValueExtractor<?>> getValueExtractors() {
        Set<ValueExtractor<?>> extractors = new LinkedHashSet<>(valueExtractors);
        if (!ignoreXmlConfiguration) {
            for (String valueExtractorClassName : bootstrapConfiguration.getValueExtractorClassNames()) {
                extractors.add(instantiate(valueExtractorClassName, ValueExtractor.class));
            }
        }
        return Set.copyOf(extractors);
    }

    @Override
    public ConstraintValidatorFactory getConstraintValidatorFactory() {
        if (constraintValidatorFactory != null) {
            return constraintValidatorFactory;
        }
        if (!ignoreXmlConfiguration && bootstrapConfiguration.getConstraintValidatorFactoryClassName() != null) {
            return instantiate(bootstrapConfiguration.getConstraintValidatorFactoryClassName(), ConstraintValidatorFactory.class);
        }
        return getDefaultConstraintValidatorFactory();
    }

    @Override
    public TraversableResolver getTraversableResolver() {
        if (traversableResolver != null) {
            return traversableResolver;
        }
        if (!ignoreXmlConfiguration && bootstrapConfiguration.getTraversableResolverClassName() != null) {
            return instantiate(bootstrapConfiguration.getTraversableResolverClassName(), TraversableResolver.class);
        }
        return getDefaultTraversableResolver();
    }

    @Override
    public ParameterNameProvider getParameterNameProvider() {
        if (parameterNameProvider != null) {
            return parameterNameProvider;
        }
        if (!ignoreXmlConfiguration && bootstrapConfiguration.getParameterNameProviderClassName() != null) {
            return instantiate(bootstrapConfiguration.getParameterNameProviderClassName(), ParameterNameProvider.class);
        }
        return getDefaultParameterNameProvider();
    }

    @Override
    public ClockProvider getClockProvider() {
        if (clockProvider != null) {
            return clockProvider;
        }
        if (!ignoreXmlConfiguration && bootstrapConfiguration.getClockProviderClassName() != null) {
            return instantiate(bootstrapConfiguration.getClockProviderClassName(), ClockProvider.class);
        }
        return getDefaultClockProvider();
    }

    @Override
    public Map<String, String> getProperties() {
        if (ignoreXmlConfiguration) {
            return Map.copyOf(properties);
        }
        Map<String, String> merged = new LinkedHashMap<>(bootstrapConfiguration.getProperties());
        merged.putAll(properties);
        return Map.copyOf(merged);
    }

    static ValidatorFactory buildValidatorFactory(ConfigurationState configurationState) {
        if (configurationState instanceof MicronautValidatorConfiguration configuration) {
            return configuration.buildValidatorFactory();
        }
        return buildValidatorFactoryInternal(configurationState);
    }

    private static ValidatorFactory buildValidatorFactoryInternal(ConfigurationState configurationState) {
        Map<String, Object> configurationProperties = new LinkedHashMap<>(configurationState.getProperties());
        if (isPresent("io.micronaut.validation.reflection.ReflectionConstraintValidatorFactory")) {
            configurationProperties.putIfAbsent("micronaut.validator.spec.reflection.enabled", "true");
        }
        ApplicationContext applicationContext = createBootstrapContext(configurationProperties);
        DefaultValidatorConfiguration validatorConfiguration = (DefaultValidatorConfiguration) applicationContext.getBean(ValidatorConfiguration.class);
        validatorConfiguration.setBeanIntrospector(BeanIntrospector.forClassLoader(applicationContext.getClassLoader()));
        if (!(configurationState instanceof MicronautValidatorConfiguration configuration) || configuration.messageInterpolatorConfigured) {
            validatorConfiguration.messageInterpolator(configurationState.getMessageInterpolator());
        }
        if (!(configurationState instanceof MicronautValidatorConfiguration configuration) || configuration.traversableResolverConfigured) {
            validatorConfiguration.traversableResolver(configurationState.getTraversableResolver());
        }
        if (!(configurationState instanceof MicronautValidatorConfiguration configuration) || configuration.constraintValidatorFactoryConfigured) {
            validatorConfiguration.constraintValidatorFactory(configurationState.getConstraintValidatorFactory());
        }
        if (!(configurationState instanceof MicronautValidatorConfiguration configuration) || configuration.parameterNameProviderConfigured) {
            validatorConfiguration.parameterNameProvider(configurationState.getParameterNameProvider());
        }
        if (!(configurationState instanceof MicronautValidatorConfiguration configuration) || configuration.clockProviderConfigured) {
            validatorConfiguration.clockProvider(configurationState.getClockProvider());
        }
        for (ValueExtractor<?> valueExtractor : configurationState.getValueExtractors()) {
            validatorConfiguration.addValueExtractor(valueExtractor);
        }
        return new BootstrapValidatorFactory(
            createValidator(validatorConfiguration),
            validatorConfiguration,
            applicationContext
        );
    }

    private Optional<ValidatorFactory> buildXmlDefaultProviderFactory() {
        String defaultProviderClassName = bootstrapConfiguration.getDefaultProviderClassName();
        if (defaultProviderClassName == null || MicronautValidationProvider.class.getName().equals(defaultProviderClassName)) {
            return Optional.empty();
        }
        for (jakarta.validation.spi.ValidationProvider<?> provider : validationProviders()) {
            if (provider.getClass().getName().equals(defaultProviderClassName)) {
                return Optional.of(provider.createGenericConfiguration(new DefaultBootstrapState())
                    .buildValidatorFactory());
            }
        }
        throw new ValidationException("Configured validation provider is not available: " + defaultProviderClassName);
    }

    private List<jakarta.validation.spi.ValidationProvider<?>> validationProviders() {
        if (bootstrapState != null && bootstrapState.getValidationProviderResolver() != null) {
            return bootstrapState.getValidationProviderResolver().getValidationProviders();
        }
        if (bootstrapState != null && bootstrapState.getDefaultValidationProviderResolver() != null) {
            return bootstrapState.getDefaultValidationProviderResolver().getValidationProviders();
        }
        List<jakarta.validation.spi.ValidationProvider<?>> providers = new ArrayList<>();
        ServiceLoader.load(jakarta.validation.spi.ValidationProvider.class, classLoader)
            .forEach(providers::add);
        return providers;
    }

    private final class DefaultBootstrapState implements BootstrapState {

        @Override
        public ValidationProviderResolver getValidationProviderResolver() {
            return bootstrapState == null ? null : bootstrapState.getValidationProviderResolver();
        }

        @Override
        public ValidationProviderResolver getDefaultValidationProviderResolver() {
            return bootstrapState == null ? null : bootstrapState.getDefaultValidationProviderResolver();
        }
    }

    static Validator createValidator(ValidatorConfiguration validatorConfiguration) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = MicronautValidatorConfiguration.class.getClassLoader();
        }
        try {
            Class<?> reflectionValidator = Class.forName("io.micronaut.validation.reflection.ReflectionValidator", false, classLoader);
            return (Validator) reflectionValidator.getConstructor(ValidatorConfiguration.class).newInstance(validatorConfiguration);
        } catch (ClassNotFoundException e) {
            return new DefaultValidator(validatorConfiguration);
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot initialize Micronaut Validation reflection fallback", e);
        }
    }

    static ApplicationContext createBootstrapContext(Map<String, Object> properties) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = MicronautValidatorConfiguration.class.getClassLoader();
        }
        ApplicationContextBuilder builder = ApplicationContext.builder()
            .classLoader(classLoader)
            .beansPredicate(beanType -> isBootstrapPackage(beanType.getBeanType().getName()))
            .beanConfigurationsPredicate(beanConfiguration -> isBootstrapPackage(beanConfiguration.getPackage().getName()))
            .eventsEnabled(false)
            .eagerBeansEnabled(false)
            .deducePackage(false)
            .bootstrapEnvironment(false)
            .deduceEnvironment(false)
            .deduceCloudEnvironment(false)
            .enableDefaultPropertySources(false)
            .environmentPropertySource(false)
            .configImport(false)
            .allowEmptyProviders(true);
        if (!properties.isEmpty()) {
            builder.propertySources(PropertySource.of(
                BOOTSTRAP_PROPERTY_SOURCE,
                properties,
                PropertySource.PropertyConvention.JAVA_PROPERTIES,
                PropertySource.Origin.of(BOOTSTRAP_PROPERTY_SOURCE)
            ));
        }
        return builder.start();
    }

    private static boolean isBootstrapPackage(String name) {
        return BOOTSTRAP_PACKAGES.stream().anyMatch(name::startsWith);
    }

    private static boolean isPresent(String className) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = MicronautValidatorConfiguration.class.getClassLoader();
        }
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private <T> T instantiate(String className, Class<T> type) {
        try {
            Class<?> loadedClass = Class.forName(className, true, classLoader);
            return type.cast(loadedClass.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot instantiate validation bootstrap class: " + className, e);
        }
    }
}
