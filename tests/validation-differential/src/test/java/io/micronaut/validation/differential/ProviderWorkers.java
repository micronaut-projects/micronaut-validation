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
package io.micronaut.validation.differential;

import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Compiles generated sources with the Micronaut processors and runs them in one isolated JVM per provider profile.
 * Every profile receives the same compiled classes, so the only variable is the validation provider.
 */
final class ProviderWorkers implements AutoCloseable {
    static final List<String> PROFILES = List.of("default", "jakarta", "reference");

    private final Map<String, Worker> workers = new java.util.LinkedHashMap<>();
    private final long deadline;

    ProviderWorkers(Path classes, long deadline) throws Exception {
        this.deadline = deadline;
        try {
            for (String profile : PROFILES) {
                workers.put(profile, new Worker(profile, classes));
            }
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    /** Runs {@code fuzz.<model>.run(validator, operation, input)} in every profile. */
    Map<String, String> run(String model, int operation, long input) throws Exception {
        Map<String, String> outcomes = new java.util.LinkedHashMap<>();
        for (var entry : workers.entrySet()) {
            outcomes.put(entry.getKey(), entry.getValue().run(model, operation, input));
        }
        return outcomes;
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        for (Worker worker : workers.values()) {
            try {
                worker.close();
            } catch (Exception e) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Compiles the sources into {@code directory/classes}.
     *
     * @return the compiler log, or {@code null} on success
     */
    static String compile(Map<String, String> sources, Path directory) throws Exception {
        if (Files.exists(directory)) {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
        Path sourceDir = directory.resolve("sources");
        Path classes = directory.resolve("classes");
        Files.createDirectories(sourceDir);
        Files.createDirectories(classes);
        List<String> arguments = new ArrayList<>(List.of("-encoding", "UTF-8", "-parameters", "-proc:full", "-d", classes.toString(),
            "-classpath", System.getProperty("fuzz.defaultClasspath"), "-processorpath", System.getProperty("fuzz.processors")));
        for (var source : sources.entrySet()) {
            Path file = sourceDir.resolve(source.getKey() + ".java");
            Files.writeString(file, source.getValue());
            arguments.add(file.toString());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK is required for generated model compilation");
        }
        Path log = directory.resolve("compiler.log");
        try (var diagnostics = Files.newOutputStream(log)) {
            if (compiler.run(null, diagnostics, diagnostics, arguments.toArray(String[]::new)) != 0) {
                return Files.readString(log);
            }
        }
        return null;
    }

    private final class Worker implements AutoCloseable {
        private final Process process;
        private final BufferedWriter requests;
        private final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<>();
        private final Path log;

        Worker(String profile, Path classes) throws Exception {
            String classpath = classes + File.pathSeparator + System.getProperty("fuzz." + profile + "Classpath");
            log = classes.getParent().resolve(profile + "-stderr.log");
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-XX:TieredStopAtLevel=1", "-cp", classpath, "io.micronaut.validation.differential.ValidationWorker", profile)
                .redirectError(log.toFile()).start();
            requests = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread.ofVirtual().start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        responses.add(line);
                    }
                } catch (Exception failure) {
                    responses.add("READER_ERROR:" + failure);
                } finally {
                    responses.add("EXITED");
                }
            });
            String ready = response("READY:");
            if (!("READY:" + profile).equals(ready)) {
                close();
                throw new IllegalStateException("Worker did not start: " + profile + "; " + log);
            }
        }

        String run(String model, int operation, long input) throws Exception {
            requests.write("fuzz." + model + "\t" + operation + "\t" + input + "\n");
            requests.flush();
            String outcome = response("OUTCOME:");
            if (!outcome.startsWith("OUTCOME:")) {
                return "WORKER_CRASH:" + outcome;
            }
            return new String(Base64.getDecoder().decode(outcome.substring(8)), StandardCharsets.UTF_8);
        }

        private String response(String prefix) throws Exception {
            while (true) {
                long remaining = Math.min(Duration.ofSeconds(30).toNanos(), deadline - System.nanoTime());
                if (remaining <= 0) {
                    throw new IllegalStateException("Differential campaign deadline reached: INCOMPLETE");
                }
                String response = responses.poll(remaining, TimeUnit.NANOSECONDS);
                if (response == null) {
                    if (System.nanoTime() >= deadline) {
                        // the wait was cut short by the campaign deadline, not by a slow worker
                        throw new IllegalStateException("Differential campaign deadline reached: INCOMPLETE");
                    }
                    return "TIMEOUT";
                }
                if (response.startsWith(prefix) || response.equals("EXITED") || response.startsWith("READER_ERROR:")) {
                    return response;
                }
            }
        }

        @Override
        public void close() throws Exception {
            try {
                requests.close();
            } finally {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }
        }
    }
}
