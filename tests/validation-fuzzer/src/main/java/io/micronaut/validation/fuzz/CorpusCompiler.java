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
package io.micronaut.validation.fuzz;

import io.micronaut.annotation.processing.AggregatingTypeElementVisitorProcessor;
import io.micronaut.annotation.processing.BeanDefinitionInjectProcessor;
import io.micronaut.annotation.processing.TypeElementVisitorProcessor;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compiles generated sources in process, with or without the Micronaut annotation processors.
 */
final class CorpusCompiler {

    private CorpusCompiler() {
    }

    /**
     * The outcome of a compilation.
     *
     * @param success Whether it succeeded
     * @param errors The error diagnostics by source file name (simple, with extension), with line numbers
     * @param output Everything the compiler reported
     */
    record Result(boolean success, Map<String, List<Diag>> errors, String output) {
    }

    /**
     * An error diagnostic.
     *
     * @param line The line
     * @param message The message
     */
    record Diag(long line, String message) {
    }

    static Result compile(Collection<Path> sources, Path output, boolean processors) throws IOException {
        Files.createDirectories(output);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StringWriter out = new StringWriter();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.US, null)) {
            List<String> options = new ArrayList<>(List.of(
                "-d", output.toString(),
                "-parameters",
                "-nowarn",
                "-Xlint:none",
                "-classpath", System.getProperty("java.class.path")
            ));
            if (!processors) {
                options.add("-proc:none");
            }
            JavaCompiler.CompilationTask task = compiler.getTask(out, fileManager, diagnostics, options, null,
                fileManager.getJavaFileObjectsFromPaths(sources));
            if (processors) {
                task.setProcessors(List.of(
                    new TypeElementVisitorProcessor(),
                    new AggregatingTypeElementVisitorProcessor(),
                    new BeanDefinitionInjectProcessor()
                ));
            }
            boolean success;
            try {
                success = Boolean.TRUE.equals(task.call());
            } catch (RuntimeException e) {
                // a processor crashing is reported as a failure of the whole compilation
                StringWriter trace = new StringWriter();
                e.printStackTrace(new java.io.PrintWriter(trace));
                Map<String, List<Diag>> errors = new LinkedHashMap<>();
                errors.computeIfAbsent("<compiler>", k -> new ArrayList<>()).add(new Diag(0, trace.toString()));
                return new Result(false, errors, trace.toString());
            }
            Map<String, List<Diag>> errors = new LinkedHashMap<>();
            StringBuilder all = new StringBuilder(out.toString());
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR) {
                    continue;
                }
                String file = d.getSource() == null ? "<none>" : Path.of(d.getSource().toUri()).getFileName().toString();
                errors.computeIfAbsent(file, k -> new ArrayList<>()).add(new Diag(d.getLineNumber(), d.getMessage(Locale.US)));
                all.append(file).append(':').append(d.getLineNumber()).append(": ").append(d.getMessage(Locale.US)).append('\n');
            }
            return new Result(success, errors, all.toString());
        }
    }
}
