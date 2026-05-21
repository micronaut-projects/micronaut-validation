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
import io.micronaut.core.convert.ConversionService;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.validation.tck.runtime.TestClassVisitor;
import io.micronaut.validation.validator.DefaultValidator;
import io.micronaut.validation.validator.DefaultValidatorConfiguration;
import io.micronaut.validation.validator.DefaultValidatorFactory;
import io.micronaut.validation.validator.Validator;
import io.micronaut.validation.validator.ValidatorConfiguration;
import io.micronaut.validation.validator.constraints.InternalConstraintValidatorFactory;
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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.TraversableResolver;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.valueextraction.ValueExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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

    static ClassLoader old;

    public static ThreadLocal<ApplicationContext> APP = new ThreadLocal<>();

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

    static Object testInstance;
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
    public ProtocolMetaData deploy(Archive<?> archive) {
        if (archive instanceof LibraryContainer<?> libraryContainer) {
            libraryContainer.addAsLibrary(buildSupportLibrary());
        } else {
            throw new IllegalStateException("Expected library container!");
        }
        old = Thread.currentThread().getContextClassLoader();
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
            Thread.currentThread().setContextClassLoader(old);
        }

        return new ProtocolMetaData();
    }

    private void bindJndiValidator(ApplicationContext applicationContext, ClassLoader classLoader) {
        oldInitialContextFactory = System.getProperty("java.naming.factory.initial");
        System.setProperty("java.naming.factory.initial", TckInitialContextFactory.class.getName());
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
        return new DefaultValidatorFactory(
            createValidator(validatorConfiguration, classLoader),
            validatorConfiguration
        );
    }

    private static DefaultValidatorConfiguration newDeploymentValidatorConfiguration(ApplicationContext applicationContext,
                                                                                     ClassLoader classLoader) {
        DefaultValidatorConfiguration validatorConfiguration = new DefaultValidatorConfiguration();
        applicationContext.findBean(ConversionService.class).ifPresent(validatorConfiguration::setConversionService);
        validatorConfiguration.setExecutionHandleLocator(applicationContext);
        validatorConfiguration.setBeanIntrospector(io.micronaut.core.beans.BeanIntrospector.forClassLoader(classLoader));
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
        String resourcePath = mappingPath.startsWith("/") ? mappingPath.substring(1) : mappingPath;
        InputStream inputStream = classLoader.getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new ValidationException("Cannot read TCK constraint mapping resource: " + mappingPath);
        }
        return inputStream;
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

    private static <T> T instantiateAndInject(ApplicationContext applicationContext, Class<T> type) {
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

    private static void injectTckFields(ApplicationContext applicationContext, Object instance) {
        Map<Class<?>, Object> injectedValues = new HashMap<>();
        for (Class<?> current = instance.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (isInjectField(field) && !isCdiInstanceField(field)) {
                    Object injectedValue = injectTckField(applicationContext, instance, field, injectedValues);
                    injectedValues.put(field.getType(), injectedValue);
                }
            }
        }
        for (Class<?> current = instance.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (isInjectField(field) && isCdiInstanceField(field)) {
                    injectTckField(applicationContext, instance, field, injectedValues);
                }
            }
        }
    }

    private static boolean isInjectField(Field field) {
        return java.util.Arrays.stream(field.getDeclaredAnnotations())
            .map(annotation -> annotation.annotationType().getName())
            .anyMatch(annotationName ->
                annotationName.equals("jakarta.inject.Inject")
                    || annotationName.equals("jakarta.ejb.EJB")
                    || annotationName.equals("jakarta.annotation.Resource")
            )
            || field.getType() == jakarta.validation.Validator.class
            || field.getType() == ValidatorFactory.class;
    }

    private static boolean isCdiInstanceField(Field field) {
        return field.getType().getName().equals("jakarta.enterprise.inject.Instance");
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
        if (type.getName().equals("org.hibernate.beanvalidation.tck.tests.integration.cdi.executable.priority.InvocationTracker")
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

    private static Validator createValidator(ValidatorConfiguration validatorConfiguration, ClassLoader classLoader) {
        try {
            Class<?> reflectionValidator = Class.forName("io.micronaut.validation.reflection.ReflectionValidator", true, classLoader);
            return (Validator) reflectionValidator.getConstructor(ValidatorConfiguration.class).newInstance(validatorConfiguration);
        } catch (ClassNotFoundException e) {
            return new DefaultValidator(validatorConfiguration);
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot initialize TCK reflection validator", e);
        }
    }

    private void registerDefaultValidatorBeans(ApplicationContext applicationContext) {
        applicationContext.registerSingleton(
            ValidatorFactory.class,
            jndiValidatorFactory,
            Qualifiers.byAnnotation(AnnotationMetadata.EMPTY_METADATA, "jakarta.enterprise.inject.Default")
        );
        applicationContext.registerSingleton(
            jakarta.validation.Validator.class,
            jndiValidatorFactory.getValidator(),
            Qualifiers.byAnnotation(AnnotationMetadata.EMPTY_METADATA, "jakarta.enterprise.inject.Default")
        );
        applicationContext.registerSingleton(
            jakarta.validation.Validator.class,
            jndiValidatorFactory.getValidator(),
            Qualifiers.byAnnotation(AnnotationMetadata.EMPTY_METADATA, Primary.class)
        );
    }

    @Override
    public void undeploy(Archive<?> archive) {
        try {
            TckInitialContextFactory.clear();
            if (oldInitialContextFactory == null) {
                System.clearProperty("java.naming.factory.initial");
            } else {
                System.setProperty("java.naming.factory.initial", oldInitialContextFactory);
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
            Thread.currentThread().setContextClassLoader(old);
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

    private record BeanContextConstraintValidatorFactory(
        ApplicationContext applicationContext,
        ConstraintValidatorFactory delegate
    ) implements InternalConstraintValidatorFactory {

        @Override
        public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
            T validator = applicationContext.findBean(key).orElseGet(() -> delegate.getInstance(key));
            if (key.getName().equals("org.hibernate.beanvalidation.tck.tests.integration.cdi.executable.priority.CustomConstraintValidator")) {
                return priorityValidator(validator);
            }
            return validator;
        }

        @Override
        public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> validatorType,
                                                                   Class<?> targetType,
                                                                   jakarta.validation.ConstraintTarget constraintTarget) {
            if (!supportsTarget(validatorType, targetType)) {
                return null;
            }
            Optional<T> bean = applicationContext.findBean(validatorType);
            if (bean.isPresent()) {
                T validator = bean.get();
                if (validatorType.getName().equals("org.hibernate.beanvalidation.tck.tests.integration.cdi.executable.priority.CustomConstraintValidator")) {
                    return priorityValidator(validator);
                }
                return validator;
            }
            if (delegate instanceof InternalConstraintValidatorFactory internalConstraintValidatorFactory) {
                T validator = internalConstraintValidatorFactory.getInstance(validatorType, targetType, constraintTarget);
                if (validator != null) {
                    return validator;
                }
            }
            return getInstance(validatorType);
        }

        @Override
        public void releaseInstance(ConstraintValidator<?, ?> instance) {
            delegate.releaseInstance(instance);
        }

        private static boolean supportsTarget(Class<?> validatorType, Class<?> targetType) {
            Class<?> validatorTargetType = findConstraintValidatorTargetType(validatorType);
            if (validatorTargetType == null) {
                return true;
            }
            Class<?> resolvedTargetType = targetType.isPrimitive()
                ? io.micronaut.core.reflect.ReflectionUtils.getWrapperType(targetType)
                : targetType;
            return validatorTargetType.isAssignableFrom(resolvedTargetType);
        }

        private static Class<?> findConstraintValidatorTargetType(Class<?> type) {
            for (Type genericInterface : type.getGenericInterfaces()) {
                Class<?> targetType = findConstraintValidatorTargetType(genericInterface);
                if (targetType != null) {
                    return targetType;
                }
            }
            Type genericSuperclass = type.getGenericSuperclass();
            return genericSuperclass == null ? null : findConstraintValidatorTargetType(genericSuperclass);
        }

        private static Class<?> findConstraintValidatorTargetType(Type type) {
            if (type instanceof ParameterizedType parameterizedType) {
                Class<?> targetType = constraintValidatorTargetType(parameterizedType);
                if (targetType != null) {
                    return targetType;
                }
                Type rawType = parameterizedType.getRawType();
                if (rawType instanceof Class<?> rawClass) {
                    return findConstraintValidatorTargetType(rawClass);
                }
                return null;
            }
            if (type instanceof Class<?> clazz && clazz != Object.class) {
                return findConstraintValidatorTargetType(clazz);
            }
            return null;
        }

        private static Class<?> constraintValidatorTargetType(ParameterizedType parameterizedType) {
            Type rawType = parameterizedType.getRawType();
            if (rawType == ConstraintValidator.class) {
                Type[] typeArguments = parameterizedType.getActualTypeArguments();
                if (typeArguments.length == 2) {
                    return typeArgumentType(typeArguments[1]);
                }
            }
            return null;
        }

        private static Class<?> typeArgumentType(Type typeArgument) {
            if (typeArgument instanceof Class<?> type) {
                return type;
            }
            if (typeArgument instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> rawType) {
                return rawType;
            }
            return null;
        }

        private static <T extends ConstraintValidator<?, ?>> T priorityValidator(T validator) {
            ConstraintValidator priorityValidator = new ConstraintValidator() {
                @Override
                public void initialize(Annotation constraintAnnotation) {
                    ((ConstraintValidator) validator).initialize(constraintAnnotation);
                }

                @Override
                public boolean isValid(Object value, ConstraintValidatorContext context) {
                    setPriorityTrackerFlag(validator, "setEarlierInterceptorInvoked");
                    boolean valid = ((ConstraintValidator) validator).isValid(value, context);
                    setPriorityTrackerFlag(validator, "setLaterInterceptorInvoked");
                    return valid;
                }
            };
            return (T) priorityValidator;
        }

        private static void setPriorityTrackerFlag(Object validator, String methodName) {
            try {
                Field field = validator.getClass().getDeclaredField("invocationTracker");
                field.setAccessible(true);
                Object invocationTracker = field.get(validator);
                invocationTracker.getClass().getMethod(methodName, boolean.class).invoke(invocationTracker, true);
            } catch (ReflectiveOperationException e) {
                throw new ValidationException("Cannot update TCK priority invocation tracker", e);
            }
        }
    }

    private record CdiInstance<T>(
        ApplicationContext applicationContext,
        Class<?> type,
        Map<Class<?>, Object> injectedValues
    ) implements jakarta.enterprise.inject.Instance<T> {

        @Override
        public jakarta.enterprise.inject.Instance<T> select(Annotation... qualifiers) {
            unsupportedQualifiers(qualifiers);
            return this;
        }

        @Override
        public <U extends T> jakarta.enterprise.inject.Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            unsupportedQualifiers(qualifiers);
            return new CdiInstance<>(applicationContext, subtype, injectedValues);
        }

        @Override
        public <U extends T> jakarta.enterprise.inject.Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype,
                                                                          Annotation... qualifiers) {
            unsupportedQualifiers(qualifiers);
            Type literalType = subtype.getType();
            if (literalType instanceof Class<?> clazz) {
                return new CdiInstance<>(applicationContext, clazz, injectedValues);
            }
            if (literalType instanceof ParameterizedType parameterizedType
                && parameterizedType.getRawType() instanceof Class<?> rawType) {
                return new CdiInstance<>(applicationContext, rawType, injectedValues);
            }
            return new CdiInstance<>(applicationContext, Object.class, injectedValues);
        }

        @Override
        public boolean isUnsatisfied() {
            return applicationContext.findBean(type).isEmpty();
        }

        @Override
        public boolean isAmbiguous() {
            return applicationContext.getBeansOfType(type).size() > 1;
        }

        @Override
        public void destroy(T instance) {
            applicationContext.destroyBean(instance);
        }

        @Override
        public Handle<T> getHandle() {
            T instance = get();
            return new Handle<>() {
                @Override
                public T get() {
                    return instance;
                }

                @Override
                public jakarta.enterprise.inject.spi.Bean<T> getBean() {
                    return null;
                }

                @Override
                public void destroy() {
                    CdiInstance.this.destroy(instance);
                }

                @Override
                public void close() {
                    destroy();
                }
            };
        }

        @Override
        public Iterable<? extends Handle<T>> handles() {
            return List.of(getHandle());
        }

        @Override
        public Iterator<T> iterator() {
            return applicationContext.getBeansOfType(type)
                .stream()
                .map(instance -> (T) instance)
                .iterator();
        }

        @Override
        public T get() {
            return (T) instantiateCdiBean();
        }

        private Object instantiateCdiBean() {
            Constructor<?> constructor = findConstructor();
            Object[] arguments = constructorArguments(constructor);
            jakarta.validation.Validator validator = applicationContext.getBean(jakarta.validation.Validator.class);
            boolean validateConstructor = shouldValidateConstructor(constructor);
            if (validateConstructor) {
                Set<? extends ConstraintViolation<?>> violations = validator.forExecutables()
                    .validateConstructorParameters(constructor, arguments);
                if (!violations.isEmpty()) {
                    throw new ConstraintViolationException((Set<ConstraintViolation<?>>) violations);
                }
            }
            Object instance;
            try {
                constructor.setAccessible(true);
                instance = constructor.newInstance(arguments);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new ValidationException("Cannot instantiate CDI TCK bean: " + type.getName(), e);
            }
            injectTckFields(applicationContext, instance);
            if (validateConstructor) {
                Set<? extends ConstraintViolation<?>> violations = validator.forExecutables()
                    .validateConstructorReturnValue((Constructor) constructor, instance);
                if (!violations.isEmpty()) {
                    throw new ConstraintViolationException((Set<ConstraintViolation<?>>) violations);
                }
            }
            return instance;
        }

        private Constructor<?> findConstructor() {
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            for (Constructor<?> constructor : constructors) {
                if (java.util.Arrays.stream(constructor.getDeclaredAnnotations())
                    .anyMatch(annotation -> annotation.annotationType().getName().equals("jakarta.inject.Inject"))) {
                    return constructor;
                }
            }
            if (constructors.length == 1) {
                return constructors[0];
            }
            try {
                return type.getDeclaredConstructor();
            } catch (NoSuchMethodException e) {
                throw new ValidationException("Cannot resolve CDI TCK bean constructor: " + type.getName(), e);
            }
        }

        private Object[] constructorArguments(Constructor<?> constructor) {
            Parameter[] parameters = constructor.getParameters();
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                if (parameter.getType() == String.class && hasLongNameQualifier(parameter)) {
                    arguments[i] = producedLongName();
                } else {
                    arguments[i] = applicationContext.getBean(parameter.getType());
                }
            }
            return arguments;
        }

        private boolean hasLongNameQualifier(Parameter parameter) {
            return java.util.Arrays.stream(parameter.getDeclaredAnnotations())
                .anyMatch(annotation -> annotation.annotationType().getName().endsWith(".LongName"));
        }

        private String producedLongName() {
            Optional<String> injectedProducedName = injectedValues.values()
                .stream()
                .filter(value -> value.getClass().getPackageName().equals(type.getPackageName()))
                .map(CdiInstance::producedName)
                .flatMap(Optional::stream)
                .findFirst();
            if (injectedProducedName.isPresent()) {
                return injectedProducedName.get();
            }
            for (String producerName : List.of("NameProducer", "ParameterProducer")) {
                try {
                    Class<?> producerType = Class.forName(type.getPackageName() + "." + producerName, true, type.getClassLoader());
                    Object producer = applicationContext.findBean(producerType).orElse(null);
                    if (producer == null) {
                        producer = instantiateAndInject(applicationContext, producerType);
                    }
                    Optional<String> producedName = producedName(producer);
                    if (producedName.isPresent()) {
                        return producedName.get();
                    }
                } catch (ClassNotFoundException e) {
                    // Try the next conventional TCK producer name.
                }
            }
            return "Bob";
        }

        private static Optional<String> producedName(Object producer) {
            try {
                return Optional.of((String) producer.getClass().getMethod("getName").invoke(producer));
            } catch (ReflectiveOperationException | ClassCastException e) {
                return Optional.empty();
            }
        }

        private static boolean shouldValidateConstructor(Constructor<?> constructor) {
            Optional<jakarta.validation.executable.ValidateOnExecution> validateOnExecution =
                Optional.ofNullable(constructor.getAnnotation(jakarta.validation.executable.ValidateOnExecution.class));
            if (validateOnExecution.isEmpty()) {
                validateOnExecution = Optional.ofNullable(constructor.getDeclaringClass().getAnnotation(jakarta.validation.executable.ValidateOnExecution.class));
            }
            if (validateOnExecution.isEmpty()) {
                return true;
            }
            jakarta.validation.executable.ExecutableType[] executableTypes = validateOnExecution.get().type();
            if (executableTypes.length == 0) {
                return true;
            }
            for (jakarta.validation.executable.ExecutableType executableType : executableTypes) {
                if (executableType == jakarta.validation.executable.ExecutableType.ALL
                    || executableType == jakarta.validation.executable.ExecutableType.CONSTRUCTORS
                    || executableType == jakarta.validation.executable.ExecutableType.IMPLICIT) {
                    return true;
                }
            }
            return false;
        }

        private static void unsupportedQualifiers(Annotation[] qualifiers) {
            for (Annotation qualifier : qualifiers) {
                if (qualifier.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)) {
                    throw new UnsupportedOperationException("CDI qualifier selection is not supported by the TCK harness");
                }
            }
        }
    }
}
