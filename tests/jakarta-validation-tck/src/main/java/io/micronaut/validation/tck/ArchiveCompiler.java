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

import io.micronaut.annotation.processing.AggregatingTypeElementVisitorProcessor;
import io.micronaut.annotation.processing.BeanDefinitionInjectProcessor;
import io.micronaut.annotation.processing.PackageConfigurationInjectProcessor;
import io.micronaut.annotation.processing.TypeElementVisitorProcessor;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.tck.runtime.VisitValidation;
import org.hibernate.beanvalidation.tck.tests.constraints.containerelement.ContainerElementConstraintCustomContainerTest;
import org.hibernate.beanvalidation.tck.tests.constraints.containerelement.ContainerElementConstraintMapKeyTest;
import org.hibernate.beanvalidation.tck.tests.constraints.containerelement.ContainerElementConstraintMapValueTest;
import org.hibernate.beanvalidation.tck.tests.constraints.containerelement.NestedContainerElementConstraintsTest;
import org.hibernate.beanvalidation.tck.tests.constraints.validatorresolution.ValidatorResolutionTest;
import org.hibernate.beanvalidation.tck.tests.validation.graphnavigation.containerelement.CascadingOnContainerElementsTest;
import org.hibernate.beanvalidation.tck.tests.validation.graphnavigation.containerelement.NestedCascadingOnContainerElementsTest;
import org.hibernate.beanvalidation.tck.tests.valueextraction.declaration.ValueExtractorDefinedInConfigurationApiTest;
import org.hibernate.beanvalidation.tck.tests.valueextraction.declaration.model.Cinema;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * IMPORTANT: assumes that it is possible to iterate through the {@code Archive}
 * and for each {@code .class} file in there, find a corresponding {@code .java}
 * file in this class's classloader. In other words, the CDI TCK source JAR must
 * be on classpath.
 */
@Internal
final class ArchiveCompiler {
    private final DeploymentDir deploymentDir;
    private final Archive<?> deploymentArchive;

    ArchiveCompiler(DeploymentDir deploymentDir, Archive<?> deploymentArchive) {
        this.deploymentDir = deploymentDir;
        this.deploymentArchive = deploymentArchive;
    }

    void compile() throws ArchiveCompilationException, ArchiveCompilerException {
        try {
            if (deploymentArchive instanceof WebArchive) {
                compileWar();
            } else {
                throw new ArchiveCompilerException("Unknown archive type: " + deploymentArchive);
            }
        } catch (IOException e) {
            throw new ArchiveCompilerException("Compilation failed", e);
        }
    }

    private void compileWar() throws ArchiveCompilationException, IOException {
        List<File> sourceFiles = new ArrayList<>();
        for (Map.Entry<ArchivePath, Node> entry : deploymentArchive.getContent().entrySet()) {
            String path = entry.getKey().get();
            if (path.startsWith("/WEB-INF/classes") && path.endsWith(".class")) {
                String sourceFile = path.replace("/WEB-INF/classes", "")
                    .replace(".class", ".java");

                if (sourceFile.contains("$") && !sourceFile.endsWith("$Dollar.java")) {
                    // skip nested classes
                    //
                    // special case for $Dollar, which is the only class in CDI TCK
                    // whose name actually intentionally contains '$'
                    //
                    // this is crude, maybe there's a better way?
                    continue;
                }

                Path sourceFilePath = deploymentDir.source.resolve(sourceFile.substring(1)); // sourceFile begins with `/`

                Files.createDirectories(sourceFilePath.getParent()); // make sure the directory exists
                try (InputStream in = ArchiveCompiler.class.getResourceAsStream(sourceFile)) {
                    if (in == null) {
                        // This might be a non-inner class defined in another class
                        continue;
                    }
                    Files.copy(in, sourceFilePath);
                }

                sourceFiles.add(sourceFilePath.toFile());
            } else if (path.startsWith("/WEB-INF/lib") && path.endsWith(".jar")) {
                String jarFile = path.replace("/WEB-INF/lib", "");
                Path jarFilePath = deploymentDir.lib.resolve(jarFile.substring(1)); // jarFile begins with `/`

                Files.createDirectories(jarFilePath.getParent()); // make sure the directory exists
                try (InputStream in = entry.getValue().getAsset().openStream()) {
                    Files.copy(in, jarFilePath);
                }
            }
        }

        doCompile(sourceFiles, deploymentDir.target.toFile());
    }

    private void doCompile(Collection<File> testSources, File outputDir) throws ArchiveCompilationException, IOException {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager mgr = compiler.getStandardFileManager(diagnostics, null, null)) {
            final String targetDir = outputDir.getAbsolutePath();
            JavaCompiler.CompilationTask task = compiler.getTask(
                null,
                mgr,
                diagnostics,
                Arrays.asList("-d", targetDir, "-verbose", "-parameters"),
                null,
                mgr.getJavaFileObjectsFromFiles(
                    Stream.concat(
                        testSources.stream(),
                        Stream.of(
                            applicationClass(testSources).toFile()
                        )
                    ).toList()
                )
            );
            task.setProcessors(getAnnotationProcessors());
            Boolean success = task.call();
            if (!Boolean.TRUE.equals(success)) {
                outputDiagnostics(diagnostics);
            }
        }
    }

    private Path applicationClass(Collection<File> testSources) throws IOException {
        List<String> importBeans = new ArrayList<>();
        String annotations = "";
        String packageName = "org.hibernate.beanvalidation.tck.tests";
        if (testSources.stream().anyMatch(f -> f.getName().contains(ContainerElementConstraintMapKeyTest.class.getSimpleName()))) {
            importBeans.add(ContainerElementConstraintMapKeyTest.class.getName() + "$TypeWithMap1");
            packageName = ContainerElementConstraintMapKeyTest.class.getPackageName();
        }
        if (testSources.stream().anyMatch(f -> f.getName().contains(ContainerElementConstraintMapValueTest.class.getSimpleName()))) {
            importBeans.add(ContainerElementConstraintMapValueTest.class.getName() + "$TypeWithMap1");
            packageName = ContainerElementConstraintMapValueTest.class.getPackageName();
        }
        if (testSources.stream().anyMatch(f -> f.getName().contains(ContainerElementConstraintCustomContainerTest.class.getSimpleName()))) {
            importBeans.add(ContainerElementConstraintCustomContainerTest.class.getName() + "$TypeWithCustomContainer1");
            packageName = ContainerElementConstraintCustomContainerTest.class.getPackageName();
        }
        if (testSources.stream().anyMatch(f -> f.getName().contains(NestedContainerElementConstraintsTest.class.getSimpleName()))) {
            importBeans.add(NestedContainerElementConstraintsTest.class.getName() + "$ListOfIterables");
            importBeans.add(NestedContainerElementConstraintsTest.class.getName() + "$ListOfMaps");
            importBeans.add(NestedContainerElementConstraintsTest.class.getName() + "$MapOfLists");
            importBeans.add(NestedContainerElementConstraintsTest.class.getName() + "$MapOfListsWithAutomaticUnwrapping");
            packageName = NestedContainerElementConstraintsTest.class.getPackageName();
        }
        if (testSources.stream().anyMatch(f -> f.getName().contains(ValueExtractorDefinedInConfigurationApiTest.class.getSimpleName()))) {
            importBeans.add(Cinema.class.getName());
            packageName = ValueExtractorDefinedInConfigurationApiTest.class.getPackageName();
        }
        if (testSources.stream().anyMatch(f -> f.getName().contains(CascadingOnContainerElementsTest.class.getSimpleName()))) {
            importBeans.add(CascadingOnContainerElementsTest.class.getName() + "$TypeWithList");
            importBeans.add(CascadingOnContainerElementsTest.class.getName() + "$TypeWithMapKey");
            importBeans.add(CascadingOnContainerElementsTest.class.getName() + "$TypeWithMapValue");
            importBeans.add(CascadingOnContainerElementsTest.class.getName() + "$TypeWithOptional");
            importBeans.add(CascadingOnContainerElementsTest.class.getName() + "$TypeWithSet");
            packageName = CascadingOnContainerElementsTest.class.getPackageName();
        }
        if (testSources.stream().anyMatch(f -> f.getName().contains(NestedCascadingOnContainerElementsTest.class.getSimpleName()))) {
            importBeans.add(NestedCascadingOnContainerElementsTest.class.getName() + "$CinemaEmailAddresses");
            importBeans.add(NestedCascadingOnContainerElementsTest.class.getName() + "$EmailAddressMap");
            importBeans.add(NestedCascadingOnContainerElementsTest.class.getName() + "$AddressBook");
            packageName = NestedCascadingOnContainerElementsTest.class.getPackageName();
        }
        if (testSources.stream().anyMatch(f -> f.getName().contains(ValidatorResolutionTest.class.getSimpleName()))) {
            importBeans.add(ValidatorResolutionTest.class.getName() + "$CustomInterfaceImpl");
            importBeans.add(ValidatorResolutionTest.class.getName() + "$AnotherSubClass");
            importBeans.add(ValidatorResolutionTest.class.getName() + "$CustomClass");
            importBeans.add(ValidatorResolutionTest.class.getName() + "$SubClassF");
            packageName = NestedCascadingOnContainerElementsTest.class.getPackageName();
        }
        if (!importBeans.isEmpty()) {
            annotations += "@" + Introspected.class.getName() + "(" +
                "visibility = io.micronaut.core.annotation.Introspected.Visibility.ANY, " +
                "accessKind = io.micronaut.core.annotation.Introspected.AccessKind.FIELD, " +
                "classNames = {" + importBeans.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(", ")) + "}" +
                ") ";
            annotations += "@" + VisitValidation.class.getName() + "(" +
                "classNames = {" + importBeans.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(", ")) + "}" +
                ") ";
        }
        final Path packagePath = deploymentDir.target.resolve(
            packageName.replace('.', '/')
        );
        Files.createDirectories(packagePath);
        final Path applicationSource = packagePath.resolve("Application.java");
        String sourceCode = "package " + packageName + ";\n" +
            annotations + " class Application {}";
        Files.writeString(applicationSource, sourceCode);
        return applicationSource;
    }

    private void outputDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) throws ArchiveCompilationException {
        throw new ArchiveCompilationException("Compilation failed:\n" + diagnostics.getDiagnostics()
            .stream()
            .map(it -> {
                System.out.println(it);
                if (it.getSource() == null) {
                    return "- " + it.getMessage(Locale.US);
                }
                Path source = deploymentDir.source.relativize(Paths.get(it.getSource().toUri().getPath()));
                return "- " + source + ":" + it.getLineNumber() + " " + it.getMessage(Locale.US);
            })
            .collect(Collectors.joining("\n")));
    }

    private List<Processor> getAnnotationProcessors() {
        List<Processor> result = new ArrayList<>();
        result.add(new TypeElementVisitorProcessor());
        result.add(new AggregatingTypeElementVisitorProcessor());
        result.add(new PackageConfigurationInjectProcessor());
        result.add(new BeanDefinitionInjectProcessor() {
            @Override
            protected boolean isProcessedAnnotation(String annotationName) {
                return true;
            }
        });
        return result;
    }

}
