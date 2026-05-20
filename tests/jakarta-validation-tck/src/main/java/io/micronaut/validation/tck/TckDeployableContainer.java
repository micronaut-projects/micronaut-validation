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
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.validation.tck.runtime.TestClassVisitor;
import io.micronaut.validation.validator.DefaultValidator;
import io.micronaut.validation.validator.DefaultValidatorConfiguration;
import io.micronaut.validation.validator.DefaultValidatorFactory;
import io.micronaut.validation.validator.Validator;
import io.micronaut.validation.validator.ValidatorConfiguration;
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
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.TraversableResolver;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.valueextraction.ValueExtractor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
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

            testInstance = applicationContext.getBean(classLoader.loadClass(testJavaClass.getName()));

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
        jndiValidatorFactory = buildValidatorFactory(applicationContext, classLoader);
        TckInitialContextFactory.bind("java:comp/ValidatorFactory", jndiValidatorFactory);
        TckInitialContextFactory.bind("java:comp/Validator", jndiValidatorFactory.getValidator());
    }

    private static ValidatorFactory buildValidatorFactory(ApplicationContext applicationContext, ClassLoader classLoader) {
        ValidatorFactory bootstrapFactory = Validation.buildDefaultValidatorFactory();
        BootstrapConfiguration bootstrapConfiguration = loadBootstrapConfiguration(classLoader).orElse(null);
        DefaultValidatorConfiguration validatorConfiguration = applicationContext.getBean(DefaultValidatorConfiguration.class);
        validatorConfiguration.setBeanIntrospector(io.micronaut.core.beans.BeanIntrospector.forClassLoader(classLoader));
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
        return new DefaultValidatorFactory(
            createValidator(validatorConfiguration, classLoader),
            validatorConfiguration
        );
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

    private static <T> T instantiate(Class<T> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot instantiate TCK validation component: " + type.getName(), e);
        }
    }

    private static void injectTckFields(ApplicationContext applicationContext, Object instance) {
        for (Class<?> current = instance.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (isInjectField(field)) {
                    injectTckField(applicationContext, instance, field);
                }
            }
        }
    }

    private static boolean isInjectField(Field field) {
        return java.util.Arrays.stream(field.getDeclaredAnnotations())
            .anyMatch(annotation -> annotation.annotationType().getName().equals("jakarta.inject.Inject"));
    }

    private static void injectTckField(ApplicationContext applicationContext, Object instance, Field field) {
        try {
            Class<Object> dependencyType = (Class<Object>) field.getType();
            Object dependency = applicationContext.findBean(dependencyType)
                .orElseGet(() -> instantiateAndInject(applicationContext, dependencyType));
            field.setAccessible(true);
            field.set(instance, dependency);
        } catch (IllegalAccessException e) {
            throw new ValidationException("Cannot inject TCK field: " + field, e);
        }
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
            ApplicationContext appContext = runningApplicationContext.get();
            if (appContext != null) {
                Thread.currentThread().setContextClassLoader(runningApplicationContext.get().getClassLoader());
                appContext.stop();
            }
            testInstance = null;

            DeploymentDir deploymentDir = this.deploymentDir.get();
            if (deploymentDir != null) {
                deleteDirectory(deploymentDir.root);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(old);
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
    ) implements ConstraintValidatorFactory {

        @Override
        public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
            return applicationContext.findBean(key).orElseGet(() -> delegate.getInstance(key));
        }

        @Override
        public void releaseInstance(ConstraintValidator<?, ?> instance) {
            delegate.releaseInstance(instance);
        }
    }
}
