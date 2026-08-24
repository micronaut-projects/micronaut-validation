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
package io.micronaut.validation.tck;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Primary;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.inject.reflection.ReflectionBeanIntrospector;
import io.micronaut.validation.tck.runtime.TestClassVisitor;
import io.micronaut.validation.validator.DefaultValidator;
import io.micronaut.validation.validator.DefaultValidatorConfiguration;
import io.micronaut.validation.validator.DefaultValidatorFactory;
import io.micronaut.validation.validator.metadata.ValidationMetadataProvider;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.context.annotation.DeploymentScoped;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.container.LibraryContainer;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.validation.BootstrapConfiguration;
import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.TraversableResolver;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.valueextraction.ValueExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

@Internal
public final class TckDeployableContainer implements DeployableContainer<TckContainerConfiguration> {

    private static final Logger LOGGER = LoggerFactory.getLogger(TckDeployableContainer.class);
    private static final String INITIAL_CONTEXT_FACTORY = "java.naming.factory.initial";
    private static final boolean REFLECTION_ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("micronaut.validation.reflection.enabled", "true"));
    private static final String PRIORITY_INVOCATION_TRACKER =
        "org.hibernate.beanvalidation.tck.tests.integration.cdi.executable.priority.InvocationTracker";

    /**
     * The application context of the deployment being validated, read by the core-profile configuration.
     */
    public static final ThreadLocal<ApplicationContext> APP = new ThreadLocal<>();

    /**
     * The instance of the test class loaded by the deployment class loader, invoked by {@link TckProtocol}.
     */
    static Object testInstance;

    private static ClassLoader harnessClassLoader;

    @Inject
    @DeploymentScoped
    private InstanceProducer<ApplicationContext> runningApplicationContext;

    @Inject
    @DeploymentScoped
    private InstanceProducer<ClassLoader> applicationClassLoader;

    @Inject
    @DeploymentScoped
    private InstanceProducer<DeploymentDir> deploymentDir;

    @Inject
    private Instance<TestClass> testClass;

    private String oldInitialContextFactory;
    private ValidatorFactory jndiValidatorFactory;

    @Override
    public void deploy(Descriptor descriptor) {
        throw new UnsupportedOperationException("Container does not support deployment of Descriptors");

    }

    @Override
    public void undeploy(Descriptor descriptor) {
        throw new UnsupportedOperationException("Container does not support deployment of Descriptors");

    }

    @Override
    public Class<TckContainerConfiguration> getConfigurationClass() {
        return TckContainerConfiguration.class;
    }

    @Override
    public void setup(TckContainerConfiguration configuration) {
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Micronaut");
    }

    private static JavaArchive buildSupportLibrary() {
        return ShrinkWrap.create(JavaArchive.class, "micronaut-validation-tck-support.jar")
            .addAsManifestResource("META-INF/services/io.micronaut.inject.visitor.TypeElementVisitor")
            .addPackage(TestClassVisitor.class.getPackage());
    }

    @Override
    @SuppressWarnings("java:S2696")
    public ProtocolMetaData deploy(Archive<?> archive) {
        if (archive instanceof LibraryContainer<?> libraryContainer) {
            libraryContainer.addAsLibrary(buildSupportLibrary());
        } else {
            throw new IllegalStateException("Expected library container!");
        }
        harnessClassLoader = Thread.currentThread().getContextClassLoader();
        if (testClass.get() == null) {
            throw new IllegalStateException("Test class not available");
        }
        Class<?> testJavaClass = testClass.get().getJavaClass();
        Objects.requireNonNull(testJavaClass);

        try {
            DeploymentDir deploymentDir = new DeploymentDir();
            this.deploymentDir.set(deploymentDir);

            new ArchiveCompiler(deploymentDir, archive).compile();

            ClassLoader classLoader = new DeploymentClassLoader(deploymentDir);
            applicationClassLoader.set(classLoader);
            Thread.currentThread().setContextClassLoader(classLoader);

            ApplicationContext applicationContext = ApplicationContext.builder()
                .classLoader(classLoader)
                .build()
                .start();
            registerDeploymentSupportSingletons(applicationContext, classLoader, deploymentDir.target);
            bindJndiValidator(applicationContext, classLoader);
            registerDefaultValidatorBeans(applicationContext);

            testInstance = instantiateAndInjectFields(applicationContext, classLoader.loadClass(testJavaClass.getName()));
            resetTckValidationProvider(classLoader);

            runningApplicationContext.set(applicationContext);
            APP.set(applicationContext);

        } catch (Throwable e) {
            throw new RuntimeException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(harnessClassLoader);
        }

        return new ProtocolMetaData();
    }

    private void bindJndiValidator(ApplicationContext applicationContext, ClassLoader classLoader) {
        oldInitialContextFactory = System.getProperty(INITIAL_CONTEXT_FACTORY);
        System.setProperty(INITIAL_CONTEXT_FACTORY, TckInitialContextFactory.class.getName());
        try {
            jndiValidatorFactory = buildValidatorFactory(applicationContext, classLoader);
        } catch (ValidationException | UnsupportedOperationException e) {
            LOGGER.debug("Falling back to XML-free JNDI validator factory for TCK deployment bootstrap", e);
            jndiValidatorFactory = buildValidatorFactoryIgnoringXml(applicationContext, classLoader);
        }
        TckInitialContextFactory.bind("java:comp/ValidatorFactory", jndiValidatorFactory);
        TckInitialContextFactory.bind("java:comp/Validator", jndiValidatorFactory.getValidator());
    }

    private static ValidatorFactory buildValidatorFactory(ApplicationContext applicationContext, ClassLoader classLoader) {
        return buildValidatorFactory(applicationContext, classLoader, true);
    }

    private static ValidatorFactory buildValidatorFactoryIgnoringXml(ApplicationContext applicationContext, ClassLoader classLoader) {
        return buildValidatorFactory(applicationContext, classLoader, false);
    }

    private static ValidatorFactory buildValidatorFactory(ApplicationContext applicationContext, ClassLoader classLoader, boolean applyXmlConfiguration) {
        ValidatorFactory bootstrapFactory = buildMicronautFactoryIgnoringXml(classLoader);
        BootstrapConfiguration bootstrapConfiguration = loadBootstrapConfiguration(classLoader).orElse(null);
        if (!applyXmlConfiguration) {
            bootstrapConfiguration = null;
        }
        DefaultValidatorConfiguration validatorConfiguration = newDeploymentValidatorConfiguration(applicationContext, classLoader);
        validatorConfiguration.setMessageInterpolator(resolveConfiguredBean(
            applicationContext,
            classLoader,
            bootstrapConfiguration == null ? null : bootstrapConfiguration.getMessageInterpolatorClassName(),
            MessageInterpolator.class
        ).orElseGet(bootstrapFactory::getMessageInterpolator));
        validatorConfiguration.setTraversableResolver(resolveConfiguredBean(
            applicationContext,
            classLoader,
            bootstrapConfiguration == null ? null : bootstrapConfiguration.getTraversableResolverClassName(),
            TraversableResolver.class
        ).orElseGet(bootstrapFactory::getTraversableResolver));
        validatorConfiguration.setParameterNameProvider(resolveConfiguredBean(
            applicationContext,
            classLoader,
            bootstrapConfiguration == null ? null : bootstrapConfiguration.getParameterNameProviderClassName(),
            ParameterNameProvider.class
        ).orElseGet(bootstrapFactory::getParameterNameProvider));
        validatorConfiguration.setClockProvider(resolveConfiguredBean(
            applicationContext,
            classLoader,
            bootstrapConfiguration == null ? null : bootstrapConfiguration.getClockProviderClassName(),
            ClockProvider.class
        ).orElseGet(bootstrapFactory::getClockProvider));
        ConstraintValidatorFactory constraintValidatorFactory = resolveConfiguredBean(
            applicationContext,
            classLoader,
            bootstrapConfiguration == null ? null : bootstrapConfiguration.getConstraintValidatorFactoryClassName(),
            ConstraintValidatorFactory.class
        ).orElseGet(() -> new BeanContextConstraintValidatorFactory(applicationContext, bootstrapFactory.getConstraintValidatorFactory()));
        validatorConfiguration.constraintValidatorFactory(constraintValidatorFactory);
        applyValueExtractors(applicationContext, classLoader, bootstrapConfiguration, validatorConfiguration);
        applyXmlMappings(classLoader, bootstrapConfiguration, validatorConfiguration);
        return new DefaultValidatorFactory(new DefaultValidator(validatorConfiguration), validatorConfiguration);
    }

    private static DefaultValidatorConfiguration newDeploymentValidatorConfiguration(ApplicationContext applicationContext,
                                                                                     ClassLoader classLoader) {
        // a Jakarta Validation provider checks the constraint definitions
        DefaultValidatorConfiguration validatorConfiguration = new DefaultValidatorConfiguration().setStrictConstraintDefinitions(true);
        applicationContext.findBean(ConversionService.class).ifPresent(validatorConfiguration::setConversionService);
        validatorConfiguration.setExecutionHandleLocator(applicationContext);
        // the generated introspections of the archive, supplemented by the reflection bridge for the types
        // without one; the same switch as MicronautValidatorConfiguration.REFLECTION_ENABLED, which the
        // harness cannot reference because the bootstrap module is not on its compile classpath
        BeanIntrospector beanIntrospector = BeanIntrospector.forClassLoader(classLoader);
        validatorConfiguration.setBeanIntrospector(REFLECTION_ENABLED
            ? new ReflectionBeanIntrospector(beanIntrospector, type -> true, true)
            : beanIntrospector);
        validatorConfiguration.setMetadataProviders(List.copyOf(applicationContext.getBeansOfType(ValidationMetadataProvider.class)));
        return validatorConfiguration;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ValidatorFactory buildMicronautFactoryIgnoringXml(ClassLoader classLoader) {
        try {
            Class providerClass = Class.forName(
                "io.micronaut.validation.bootstrap.MicronautValidationProvider",
                true,
                classLoader
            ).asSubclass(jakarta.validation.spi.ValidationProvider.class);
            return Validation.byProvider(providerClass)
                .configure()
                .ignoreXmlConfiguration()
                .buildValidatorFactory();
        } catch (ClassNotFoundException e) {
            return new DefaultValidatorFactory();
        }
    }

    private static void registerDeploymentSupportSingletons(ApplicationContext applicationContext,
                                                            ClassLoader classLoader,
                                                            Path targetDirectory) throws IOException {
        try (Stream<Path> classFiles = Files.walk(targetDirectory)) {
            classFiles
                .filter(path -> path.getFileName().toString().equals("Greeter.class"))
                .map(targetDirectory::relativize)
                .map(TckDeployableContainer::className)
                .forEach(className -> registerSupportSingleton(applicationContext, classLoader, className));
        }
    }

    private static String className(Path classFile) {
        String className = classFile.toString()
            .replace('/', '.')
            .replace('\\', '.');
        return className.substring(0, className.length() - ".class".length());
    }

    private static void registerSupportSingleton(ApplicationContext applicationContext, ClassLoader classLoader, String className) {
        try {
            Class<?> type = Class.forName(className, true, classLoader);
            if (applicationContext.findBean(type).isEmpty()) {
                applicationContext.registerSingleton((Class) type, instantiate(type), null, false);
            }
        } catch (ClassNotFoundException e) {
            throw new ValidationException("Cannot load TCK support class: " + className, e);
        }
    }

    private static Optional<BootstrapConfiguration> loadBootstrapConfiguration(ClassLoader classLoader) {
        try {
            Class<?> loaderType = Class.forName("io.micronaut.validation.xml.ValidationXmlBootstrapConfigurationLoader", true, classLoader);
            Object loader = loaderType.getDeclaredConstructor().newInstance();
            return (Optional<BootstrapConfiguration>) loaderType.getMethod("load", ClassLoader.class).invoke(loader, classLoader);
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot read TCK validation.xml", e);
        }
    }

    private static void applyXmlMappings(ClassLoader classLoader,
                                         BootstrapConfiguration bootstrapConfiguration,
                                         DefaultValidatorConfiguration validatorConfiguration) {
        if (bootstrapConfiguration == null || bootstrapConfiguration.getConstraintMappingResourcePaths().isEmpty()) {
            return;
        }
        xmlMappingMetadataProvider(classLoader, bootstrapConfiguration)
            .ifPresent(provider -> {
                List<ValidationMetadataProvider> metadataProviders = new ArrayList<>(validatorConfiguration.getMetadataProviders());
                metadataProviders.add(provider);
                validatorConfiguration.setMetadataProviders(metadataProviders);
            });
    }

    private static Optional<ValidationMetadataProvider> xmlMappingMetadataProvider(ClassLoader classLoader,
                                                                                  BootstrapConfiguration bootstrapConfiguration) {
        Set<InputStream> mappingStreams = new LinkedHashSet<>();
        for (String mappingPath : bootstrapConfiguration.getConstraintMappingResourcePaths()) {
            mappingStreams.add(getConstraintMappingResource(classLoader, mappingPath));
        }
        try {
            Class<?> providerClass = Class.forName("io.micronaut.validation.xml.XmlValidationMetadataProvider", true, classLoader);
            return Optional.of((ValidationMetadataProvider) providerClass
                .getConstructor(ClassLoader.class, Set.class)
                .newInstance(classLoader, mappingStreams));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot initialize TCK XML validation metadata provider", e);
        }
    }

    private static InputStream getConstraintMappingResource(ClassLoader classLoader, String mappingPath) {
        String resourcePath = normalizeClasspathResource(mappingPath);
        InputStream inputStream = classLoader.getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new ValidationException("Cannot read TCK constraint mapping resource: " + mappingPath);
        }
        return inputStream;
    }

    private static String normalizeClasspathResource(String mappingPath) {
        String resourcePath = mappingPath == null ? "" : mappingPath.trim();
        if (resourcePath.isEmpty()) {
            throw new ValidationException("Invalid TCK constraint mapping resource path: path is empty");
        }
        if (resourcePath.startsWith("//")) {
            throw new ValidationException("Invalid TCK constraint mapping resource path: " + mappingPath);
        }
        if (resourcePath.startsWith("/")) {
            resourcePath = resourcePath.substring(1);
        }
        if (resourcePath.isEmpty() || resourcePath.endsWith("/") || resourcePath.contains("\\") || resourcePath.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw new ValidationException("Invalid TCK constraint mapping resource path: " + mappingPath);
        }
        String[] segments = resourcePath.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new ValidationException("Invalid TCK constraint mapping resource path: " + mappingPath);
            }
        }
        return resourcePath;
    }

    private static <T> Optional<T> resolveConfiguredBean(ApplicationContext applicationContext,
                                                        ClassLoader classLoader,
                                                        String className,
                                                        Class<T> type) {
        if (className == null) {
            return Optional.empty();
        }
        try {
            Class<? extends T> configuredType = Class.forName(className, true, classLoader).asSubclass(type);
            return Optional.of(type.cast(resolveBean(applicationContext, configuredType)));
        } catch (ClassNotFoundException e) {
            throw new ValidationException("Cannot load TCK validation component: " + className, e);
        }
    }

    private static <T> T resolveBean(ApplicationContext applicationContext, Class<T> type) {
        return applicationContext.findBean(type)
            .orElseGet(() -> instantiateAndInject(applicationContext, type));
    }

    static <T> T instantiateAndInject(ApplicationContext applicationContext, Class<T> type) {
        T instance = applicationContext.inject(instantiate(type));
        injectTckFields(applicationContext, instance);
        return instance;
    }

    private static <T> T instantiateAndInjectFields(ApplicationContext applicationContext, Class<T> type) {
        T instance = instantiate(type);
        injectTckFields(applicationContext, instance);
        return instance;
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot instantiate TCK validation component: " + type.getName(), e);
        }
    }

    static void injectTckFields(ApplicationContext applicationContext, Object instance) {
        Map<Class<?>, Object> injectedValues = new HashMap<>();
        injectTckFields(applicationContext, instance, injectedValues, false);
        injectTckFields(applicationContext, instance, injectedValues, true);
    }

    private static void injectTckFields(ApplicationContext applicationContext,
                                        Object instance,
                                        Map<Class<?>, Object> injectedValues,
                                        boolean cdiInstanceFields) {
        for (Class<?> current = instance.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (isInjectField(field) && isCdiInstanceField(field) == cdiInstanceFields) {
                    Object injectedValue = injectTckField(applicationContext, instance, field, injectedValues);
                    if (!cdiInstanceFields) {
                        injectedValues.put(field.getType(), injectedValue);
                    }
                }
            }
        }
    }

    private static boolean isInjectField(Field field) {
        return java.util.Arrays.stream(field.getDeclaredAnnotations())
            .map(Annotation::annotationType)
            .anyMatch(annotationType ->
                annotationType == jakarta.inject.Inject.class
                    || annotationType == jakarta.ejb.EJB.class
                    || annotationType == jakarta.annotation.Resource.class
            )
            || field.getType() == Validator.class
            || field.getType() == ValidatorFactory.class;
    }

    private static boolean isCdiInstanceField(Field field) {
        return field.getType() == jakarta.enterprise.inject.Instance.class;
    }

    private static Object injectTckField(ApplicationContext applicationContext,
                                         Object instance,
                                         Field field,
                                         Map<Class<?>, Object> injectedValues) {
        try {
            Object dependency = resolveTckField(applicationContext, field, injectedValues);
            registerSharedPriorityTracker(applicationContext, field.getType(), dependency);
            field.setAccessible(true);
            field.set(instance, dependency);
            return dependency;
        } catch (IllegalAccessException e) {
            throw new ValidationException("Cannot inject TCK field: " + field, e);
        }
    }

    private static Object resolveTckField(ApplicationContext applicationContext,
                                          Field field,
                                          Map<Class<?>, Object> injectedValues) {
        if (isCdiInstanceField(field)) {
            return new CdiInstance<>(applicationContext, cdiInstanceType(field), injectedValues);
        }
        Class<Object> dependencyType = (Class<Object>) field.getType();
        return applicationContext.findBean(dependencyType)
            .orElseGet(() -> instantiateAndInject(applicationContext, dependencyType));
    }

    private static void registerSharedPriorityTracker(ApplicationContext applicationContext, Class<?> type, Object dependency) {
        if (PRIORITY_INVOCATION_TRACKER.equals(type.getName())
            && applicationContext.findBean(
                (Class) type,
                Qualifiers.byAnnotation(AnnotationMetadata.EMPTY_METADATA, Primary.class)
            ).isEmpty()) {
            applicationContext.registerSingleton(
                (Class) type,
                dependency,
                Qualifiers.byAnnotation(AnnotationMetadata.EMPTY_METADATA, Primary.class),
                false
            );
        }
    }

    private static Class<?> cdiInstanceType(Field field) {
        Type genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType parameterizedType) {
            Type type = parameterizedType.getActualTypeArguments()[0];
            if (type instanceof Class<?> clazz) {
                return clazz;
            }
            if (type instanceof ParameterizedType nestedParameterizedType
                && nestedParameterizedType.getRawType() instanceof Class<?> rawType) {
                return rawType;
            }
        }
        return Object.class;
    }

    private static void applyValueExtractors(ApplicationContext applicationContext,
                                             ClassLoader classLoader,
                                             BootstrapConfiguration bootstrapConfiguration,
                                             DefaultValidatorConfiguration validatorConfiguration) {
        ServiceLoader.load(ValueExtractor.class, classLoader)
            .stream()
            .map(ServiceLoader.Provider::type)
            .map(type -> resolveBean(applicationContext, type))
            .forEach(validatorConfiguration::addValueExtractor);
        if (bootstrapConfiguration != null) {
            for (String valueExtractorClassName : bootstrapConfiguration.getValueExtractorClassNames()) {
                resolveConfiguredBean(applicationContext, classLoader, valueExtractorClassName, ValueExtractor.class)
                    .ifPresent(validatorConfiguration::replaceValueExtractor);
            }
        }
    }

    private void registerDefaultValidatorBeans(ApplicationContext applicationContext) {
        applicationContext.registerSingleton(
            ValidatorFactory.class,
            jndiValidatorFactory,
            Qualifiers.byAnnotation(AnnotationMetadata.EMPTY_METADATA, "jakarta.enterprise.inject.Default")
        );
        applicationContext.registerSingleton(
            Validator.class,
            jndiValidatorFactory.getValidator(),
            Qualifiers.byAnnotation(AnnotationMetadata.EMPTY_METADATA, "jakarta.enterprise.inject.Default")
        );
        applicationContext.registerSingleton(
            Validator.class,
            jndiValidatorFactory.getValidator(),
            Qualifiers.byAnnotation(AnnotationMetadata.EMPTY_METADATA, Primary.class)
        );
    }

    @Override
    public void undeploy(Archive<?> archive) {
        try {
            TckInitialContextFactory.clear();
            if (oldInitialContextFactory == null) {
                System.clearProperty(INITIAL_CONTEXT_FACTORY);
            } else {
                System.setProperty(INITIAL_CONTEXT_FACTORY, oldInitialContextFactory);
            }
            if (jndiValidatorFactory != null) {
                jndiValidatorFactory.close();
                jndiValidatorFactory = null;
            }
            ClassLoader classLoader = applicationClassLoader.get();
            if (classLoader != null) {
                resetTckValidationProvider(classLoader);
            }
            ApplicationContext appContext = runningApplicationContext.get();
            if (appContext != null) {
                Thread.currentThread().setContextClassLoader(runningApplicationContext.get().getClassLoader());
                appContext.close();
            }
            APP.remove();
            testInstance = null;
            closeClassLoader(classLoader);

            DeploymentDir deploymentDir = this.deploymentDir.get();
            if (deploymentDir != null) {
                deleteDirectory(deploymentDir.root);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(harnessClassLoader);
        }
    }

    private static void resetTckValidationProvider(ClassLoader classLoader) {
        try {
            Class<?> testUtil = Class.forName("org.hibernate.beanvalidation.tck.util.TestUtil", false, classLoader);
            Field provider = testUtil.getDeclaredField("validationProviderUnderTest");
            provider.setAccessible(true);
            provider.set(null, null);
        } catch (ClassNotFoundException e) {
            // Some deployments do not include the TCK TestUtil support class.
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot reset TCK validation provider cache", e);
        }
    }

    private static void closeClassLoader(ClassLoader classLoader) {
        if (classLoader instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOGGER.warn("Unable to close deployment classloader", e);
            }
        }
    }

    private static void deleteDirectory(Path dir) {
        try {
            Files.walkFileTree(dir, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Unable to delete directory: " + dir, e);
        }
    }
}
