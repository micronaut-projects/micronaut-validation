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
package io.micronaut.validation.tck;

import org.testng.annotations.Test;

import javax.naming.CompositeName;
import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.OperationNotSupportedException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public final class ArchiveCompilerTest {

    @Test
    public void archivePathsMustStayUnderDeploymentRoot() throws Exception {
        Path root = Files.createTempDirectory("archive-compiler-test-");

        assertEquals(
            ArchiveCompiler.resolveUnderRoot(root, "org/example/Test.java"),
            root.toAbsolutePath().normalize().resolve("org/example/Test.java")
        );
    }

    @Test
    public void archivePathsRejectTraversalAndExternalLocations() throws Exception {
        for (String relativePath : new String[]{
            "",
            "../escape.txt",
            "org/../../escape.txt",
            "org\\example\\Test.java",
            "file:/tmp/Test.java"
        }) {
            Path root = Files.createTempDirectory("archive-compiler-test-");
            expectThrows(ArchiveCompilerException.class, () -> ArchiveCompiler.resolveUnderRoot(root, relativePath));
            deleteDirectory(root);
        }
    }

    @Test
    public void deploymentDirectoryCreatesExpectedLayout() throws Exception {
        DeploymentDir deploymentDir = new DeploymentDir();

        try {
            assertTrue(Files.isDirectory(deploymentDir.root));
            assertTrue(Files.isDirectory(deploymentDir.source));
            assertTrue(Files.isDirectory(deploymentDir.target));
            assertTrue(Files.isDirectory(deploymentDir.lib));
        } finally {
            deleteDirectory(deploymentDir.root);
        }
    }

    @Test
    public void deploymentClassLoaderPrefersDeploymentResources() throws Exception {
        DeploymentDir deploymentDir = new DeploymentDir();
        Files.createDirectories(deploymentDir.target.resolve("META-INF"));
        Files.writeString(deploymentDir.target.resolve("META-INF/tck-resource.txt"), "deployment", StandardCharsets.UTF_8);

        try (DeploymentClassLoader classLoader = new DeploymentClassLoader(deploymentDir)) {
            assertNotNull(classLoader.getResource("META-INF/tck-resource.txt"));
            try (InputStream inputStream = classLoader.getResourceAsStream("META-INF/tck-resource.txt")) {
                assertNotNull(inputStream);
                assertEquals(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8), "deployment");
            }
            assertEquals(classLoader.getResourceAsStream("META-INF/missing-resource.txt"), null);
            Enumeration<java.net.URL> resources = classLoader.getResources("META-INF/tck-resource.txt");
            assertTrue(resources.hasMoreElements());
            resources.nextElement();
            assertFalse(resources.hasMoreElements());
        } finally {
            deleteDirectory(deploymentDir.root);
        }
    }

    @Test
    public void initialContextFactoryExposesBoundValuesOnly() throws Exception {
        TckInitialContextFactory.clear();
        TckInitialContextFactory.bind("java:comp/Validator", "validator");
        Context context = new TckInitialContextFactory().getInitialContext(new Hashtable<>());

        try {
            assertEquals(context.lookup("java:comp/Validator"), "validator");
            assertEquals(context.lookup(new CompositeName("java:comp/Validator")), "validator");
            assertEquals(context.lookupLink("java:comp/Validator"), "validator");
            assertEquals(context.getEnvironment().size(), 0);
            assertEquals(context.getNameInNamespace(), "");
            assertEquals(context.composeName("name", "prefix/"), "prefix/name");
            expectThrows(NameNotFoundException.class, () -> context.lookup("missing"));
            expectThrows(OperationNotSupportedException.class, () -> context.bind("other", "value"));
        } finally {
            context.close();
            TckInitialContextFactory.clear();
        }
    }

    @Test
    public void tckContainerConfigurationHasNoExternalValidationRequirements() {
        new TckContainerConfiguration().validate();
    }

    private static void deleteDirectory(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
