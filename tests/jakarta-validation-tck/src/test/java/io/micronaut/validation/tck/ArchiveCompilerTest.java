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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

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
            try {
                ArchiveCompiler.resolveUnderRoot(Files.createTempDirectory("archive-compiler-test-"), relativePath);
                fail("Expected path to be rejected: " + relativePath);
            } catch (ArchiveCompilerException e) {
                // expected
            }
        }
    }
}
