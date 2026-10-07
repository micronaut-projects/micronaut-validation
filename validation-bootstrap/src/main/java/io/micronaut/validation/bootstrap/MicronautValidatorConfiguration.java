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
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.validation.validator.DefaultValidator;
import io.micronaut.validation.validator.DefaultValidatorConfiguration;
import io.micronaut.validation.validator.ReflectionSupport;
import io.micronaut.validation.validator.Validator;
import io.micronaut.validation.validator.ValidatorConfiguration;
import io.micronaut.validation.validator.constraints.DefaultInternalConstraintValidatorFactory;
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
import jakarta.validation.spi.ValidationProvider;
import jakarta.validation.valueextraction.ValueExtractor;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Internal Jakarta Validation {@link Configuration} implementation used by {@link
 * MicronautValidationProvider} during ServiceLoader bootstrap.
 *
 * @since 5.3.0
 */
@Internal
public final class MicronautValidatorConfiguration
        implements Configuration<MicronautValidatorConfiguration>, ConfigurationState {

    private static final String BOOTSTRAP_PROPERTY_SOURCE = "micronaut-validation-bootstrap";
    private static final Set<String> BOOTSTRAP_PACKAGES = Set.of(
        "io.micronaut.validation",
        "io.micronaut.inject",
        "io.micronaut.context",
        "io.micronaut.core.convert",
        "io.micronaut.core.io.service"
    );

    // a Jakarta Validation provider checks the constraint definitions
    private final DefaultValidatorConfiguration defaults = new DefaultValidatorConfiguration().setStrictConstraintDefinitions(true);
    private final List<byte[]> mappingStreams = new ArrayList<>();
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
    private MessageInterpolator defaultMessageInterpolator;
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

    /** Creates a configuration. */
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
        defaults.setBeanIntrospector(BeanIntrospector.forClassLoader(classLoader));
        bootstrapConfiguration = BootstrapServiceDiscovery.configurationLoaders(classLoader)
            .stream()
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
        defaults.addValueExtractor(extractor);
        valueExtractors.add(extractor);
        return this;
    }

    @Override
    public MicronautValidatorConfiguration addMapping(InputStream stream) {
        try (stream) {
            mappingStreams.add(stream.readAllBytes());
        } catch (IOException e) {
            throw new ValidationException("Cannot read constraint mapping stream", e);
        }
        return this;
    }

    @Override
    public MicronautValidatorConfiguration addProperty(String name, String value) {
        properties.put(name, value);
        return this;
    }

    @Override
    public MessageInterpolator getDefaultMessageInterpolator() {
        if (defaultMessageInterpolator == null) {
            defaultMessageInterpolator = createElMessageInterpolator().orElseGet(defaults::getDefaultMessageInterpolator);
        }
        return defaultMessageInterpolator;
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
        Set<InputStream> streams = new LinkedHashSet<>();
        for (byte[] mappingStream : mappingStreams) {
            streams.add(new ByteArrayInputStream(mappingStream));
        }
        try {
            if (!ignoreXmlConfiguration) {
            for (String mappingPath : bootstrapConfiguration.getConstraintMappingResourcePaths()) {
                streams.add(getConstraintMappingResource(mappingPath));
            }
        }
        return Set.copyOf(streams);
        } catch (RuntimeException failure) {
            for (InputStream stream : streams) {
                try {
                    stream.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private InputStream getConstraintMappingResource(String mappingPath) {
        String resourcePath = ValidationResourcePaths.normalizeClasspathResource(mappingPath, "constraint mapping");
        InputStream inputStream = classLoader.getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new ValidationException("Cannot read constraint mapping resource: " + mappingPath);
        }
        return inputStream;
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
        ClassLoader loader = configurationState instanceof MicronautValidatorConfiguration configuration
            ? configuration.classLoader : ReflectionSupport.get().classLoader();
        ApplicationContext applicationContext = createBootstrapContext(configurationProperties, loader);
        DefaultValidatorConfiguration validatorConfiguration = (DefaultValidatorConfiguration) applicationContext.getBean(ValidatorConfiguration.class);
        // a factory bootstrapped through the Jakarta API checks constraint definitions as the specification
        // has it, which the bean of an application only does when configured to
        validatorConfiguration.setStrictConstraintDefinitions(true);
        // the generated introspections of the application, supplemented by the reflection bridge of
        // micronaut-core for
        // the types without one: the validator reads them, the factory instantiates the constraint
        // validators through them
        validatorConfiguration.setReflectionSupport(ReflectionSupport.forClassLoader(loader));
        BeanIntrospector beanIntrospector = validatorConfiguration.getReflectionSupport()
            .supplemented(BeanIntrospector.forClassLoader(loader));
        validatorConfiguration.setBeanIntrospector(beanIntrospector);
        // the constraint mappings decorate the introspector: a mapped bean is described by its introspection
        // with what the mapping declares, and the validator reads nothing else
        applyConstraintMappings(validatorConfiguration, configurationState.getMappingStreams());
        validatorConfiguration.constraintValidatorFactory(new DefaultInternalConstraintValidatorFactory(
            validatorConfiguration.getBeanIntrospector(), applicationContext, validatorConfiguration.getReflectionSupport()));
        if (shouldApplyMessageInterpolator(configurationState)) {
            validatorConfiguration.messageInterpolator(configurationState.getMessageInterpolator());
        }
        if (shouldApplyTraversableResolver(configurationState)) {
            validatorConfiguration.traversableResolver(configurationState.getTraversableResolver());
        }
        if (shouldApplyConstraintValidatorFactory(configurationState)) {
            validatorConfiguration.constraintValidatorFactory(configurationState.getConstraintValidatorFactory());
        }
        if (shouldApplyParameterNameProvider(configurationState)) {
            validatorConfiguration.parameterNameProvider(configurationState.getParameterNameProvider());
        }
        if (shouldApplyClockProvider(configurationState)) {
            validatorConfiguration.clockProvider(configurationState.getClockProvider());
        }
        applyValueExtractors(configurationState, validatorConfiguration);
        return new BootstrapValidatorFactory(
            createValidator(validatorConfiguration),
            validatorConfiguration,
            applicationContext
        );
    }

    private static void applyValueExtractors(ConfigurationState configurationState,
                                             DefaultValidatorConfiguration validatorConfiguration) {
        if (configurationState instanceof MicronautValidatorConfiguration configuration) {
            configuration.applyValueExtractors(validatorConfiguration);
            return;
        }
        for (ValueExtractor<?> valueExtractor : configurationState.getValueExtractors()) {
            validatorConfiguration.addValueExtractor(valueExtractor);
        }
    }

    private void applyValueExtractors(DefaultValidatorConfiguration validatorConfiguration) {
        BootstrapServiceDiscovery.services(ValueExtractor.class, classLoader)
            .forEach(validatorConfiguration::addValueExtractor);
        if (!ignoreXmlConfiguration) {
            DefaultValidatorConfiguration xmlDuplicateCheck = new DefaultValidatorConfiguration();
            for (String valueExtractorClassName : bootstrapConfiguration.getValueExtractorClassNames()) {
                ValueExtractor<?> valueExtractor = instantiate(valueExtractorClassName, ValueExtractor.class);
                xmlDuplicateCheck.addValueExtractor(valueExtractor);
                validatorConfiguration.replaceValueExtractor(valueExtractor);
            }
        }
        for (ValueExtractor<?> valueExtractor : valueExtractors) {
            validatorConfiguration.replaceValueExtractor(valueExtractor);
        }
    }

    private static boolean shouldApplyMessageInterpolator(ConfigurationState configurationState) {
        return !(configurationState instanceof MicronautValidatorConfiguration configuration)
            || configuration.messageInterpolatorConfigured
            || configuration.hasXmlMessageInterpolator();
    }

    private static boolean shouldApplyTraversableResolver(ConfigurationState configurationState) {
        return !(configurationState instanceof MicronautValidatorConfiguration configuration)
            || configuration.traversableResolverConfigured
            || configuration.hasXmlTraversableResolver();
    }

    private static boolean shouldApplyConstraintValidatorFactory(ConfigurationState configurationState) {
        return !(configurationState instanceof MicronautValidatorConfiguration configuration)
            || configuration.constraintValidatorFactoryConfigured
            || configuration.hasXmlConstraintValidatorFactory();
    }

    private static boolean shouldApplyParameterNameProvider(ConfigurationState configurationState) {
        return !(configurationState instanceof MicronautValidatorConfiguration configuration)
            || configuration.parameterNameProviderConfigured
            || configuration.hasXmlParameterNameProvider();
    }

    private static boolean shouldApplyClockProvider(ConfigurationState configurationState) {
        return !(configurationState instanceof MicronautValidatorConfiguration configuration)
            || configuration.clockProviderConfigured
            || configuration.hasXmlClockProvider();
    }

    private boolean hasXmlMessageInterpolator() {
        return !ignoreXmlConfiguration && bootstrapConfiguration.getMessageInterpolatorClassName() != null;
    }

    private boolean hasXmlTraversableResolver() {
        return !ignoreXmlConfiguration && bootstrapConfiguration.getTraversableResolverClassName() != null;
    }

    private boolean hasXmlConstraintValidatorFactory() {
        return !ignoreXmlConfiguration && bootstrapConfiguration.getConstraintValidatorFactoryClassName() != null;
    }

    private boolean hasXmlParameterNameProvider() {
        return !ignoreXmlConfiguration && bootstrapConfiguration.getParameterNameProviderClassName() != null;
    }

    private boolean hasXmlClockProvider() {
        return !ignoreXmlConfiguration && bootstrapConfiguration.getClockProviderClassName() != null;
    }

    private Optional<ValidatorFactory> buildXmlDefaultProviderFactory() {
        String defaultProviderClassName = bootstrapConfiguration.getDefaultProviderClassName();
        if (defaultProviderClassName == null || MicronautValidationProvider.class.getName().equals(defaultProviderClassName)) {
            return Optional.empty();
        }
        for (ValidationProvider<?> provider : validationProviders()) {
            if (provider.getClass().getName().equals(defaultProviderClassName)) {
                return Optional.of(provider.createGenericConfiguration(new DefaultBootstrapState())
                    .buildValidatorFactory());
            }
        }
        throw new ValidationException("Configured validation provider is not available: " + defaultProviderClassName);
    }

    private List<ValidationProvider<?>> validationProviders() {
        if (bootstrapState != null && bootstrapState.getValidationProviderResolver() != null) {
            return bootstrapState.getValidationProviderResolver().getValidationProviders();
        }
        if (bootstrapState != null && bootstrapState.getDefaultValidationProviderResolver() != null) {
            return bootstrapState.getDefaultValidationProviderResolver().getValidationProviders();
        }
        List<ValidationProvider<?>> providers = new ArrayList<>();
        BootstrapServiceDiscovery.services(ValidationProvider.class, classLoader)
            .forEach(providers::add);
        return providers;
    }

    static Validator createValidator(ValidatorConfiguration validatorConfiguration) {
        return new DefaultValidator(validatorConfiguration);
    }

    /**
     * The generated introspections, supplemented by the reflection bridge of micronaut-core for the
     * types without one, unless the system property
     * {@code micronaut.validation.reflection.enabled} is {@code false}.
     *
     * @param beanIntrospector The introspector of the generated introspections
     * @return The introspector the validator reads
     */
    public static BeanIntrospector supplemented(BeanIntrospector beanIntrospector) {
        return ReflectionSupport.get().supplemented(beanIntrospector);
    }

    /**
     * @return Whether the types without a generated introspection are described reflectively
     */
    public static boolean isReflectionEnabled() {
        return ReflectionSupport.get().isReflectionEnabled();
    }

    static ApplicationContext createBootstrapContext(Map<String, Object> properties) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = MicronautValidatorConfiguration.class.getClassLoader();
        }
        return createBootstrapContext(properties, classLoader);
    }

    private static ApplicationContext createBootstrapContext(Map<String, Object> properties, ClassLoader classLoader) {
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

    private static void applyConstraintMappings(DefaultValidatorConfiguration validatorConfiguration, Set<InputStream> mappingStreams) {
        if (mappingStreams.isEmpty()) {
            return;
        }
        ConstraintMappingConfigurer configurer =
                BootstrapServiceDiscovery.mappingConfigurer(validatorConfiguration.getReflectionSupport().classLoader())
                        .orElse(null);
        if (configurer == null) {
            ValidationException failure =
                    new ValidationException(
                            "XML constraint mappings require micronaut-validation-xml");
            for (InputStream stream : mappingStreams) {
                try {
                    stream.close();
                } catch (IOException e) {
                    failure.addSuppressed(e);
                }
            }
            throw failure;
        }
        configurer.configure(validatorConfiguration, mappingStreams);
    }

    private Optional<MessageInterpolator> createElMessageInterpolator() {
        return BootstrapServiceDiscovery.interpolators(classLoader)
                .stream()
                .map(provider -> provider.create(classLoader))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private <T> T instantiate(String className, Class<T> type) {
        var introspection =
                BeanIntrospector.forClassLoader(classLoader)
                        .findIntrospections(reference -> reference.getName().equals(className))
                        .stream()
                        .findFirst()
                        .orElse(null);
        Object instance;
        try {
            instance = introspection == null
                    ? defaults.getReflectionSupport().instantiate(className, classLoader)
                    : introspection.instantiate();
        } catch (ValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ValidationException("Cannot instantiate bootstrap class: " + className, e);
        }
        if (instance == null) {
            throw new ValidationException(
                    "No constructor is available for bootstrap class: " + className);
        }
        return type.cast(instance);
    }

    private final class DefaultBootstrapState implements BootstrapState {

        @Override
        @Nullable
        public ValidationProviderResolver getValidationProviderResolver() {
            return bootstrapState == null ? null : bootstrapState.getValidationProviderResolver();
        }

        @Override
        @Nullable
        public ValidationProviderResolver getDefaultValidationProviderResolver() {
            return bootstrapState == null ? null : bootstrapState.getDefaultValidationProviderResolver();
        }
    }
}
