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

import org.junit.jupiter.api.Test;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic source generation, isolated providers, shrinking and durable reproductions. */
class DifferentialCampaignTest {
    private final long seed = Long.parseLong(System.getProperty("fuzz.seed", "631530"));
    private final int requested = Integer.parseInt(System.getProperty("fuzz.count", "2000"));
    private final Path output = Path.of(System.getProperty("fuzz.output"));
    private final long deadline = System.nanoTime() + Duration.ofMinutes(Long.parseLong(System.getProperty("fuzz.limitMinutes", "30"))).toNanos();
    private int completed;
    private int minimizedId;

    @Test
    void generatedModelsMatchTheReferenceAndDefaultCapabilitiesAreExplicit() throws Exception {
        assertTrue(requested > 0);
        Files.createDirectories(output);
        var random = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        writeStatus("RUNNING");
        try {
            replayCorpus();
            int batch = 0;
            while (completed < requested) {
                checkDeadline();
                List<ModelDescription> models = new ArrayList<>();
                int count = Math.min(25, (requested - completed + 7) / 8);
                for (int i = 0; i < count; i++) { models.add(ModelDescription.generate("Model" + batch + "_" + i, random)); }
                Path classes = compile(models, output.resolve("batch-" + batch++));
                try (Workers workers = new Workers(classes)) {
                    for (ModelDescription model : models) {
                        for (int input = 0; input < 4 && completed < requested; input++) {
                            int operation = random.nextInt(3);
                            long value = random.nextLong(-20, 21);
                            compare(workers, model, operation, value, true);
                        }
                    }
                }
            }
            writeStatus("COMPLETE");
            System.out.println("Differential campaign complete: seed=" + seed + ", comparisons=" + completed
                + ", reference=Hibernate Validator 9.1.4.Final");
        } catch (Throwable failure) {
            writeStatus(Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline ? "INCOMPLETE" : "FAILED");
            throw failure;
        }
    }

    private void replayCorpus() throws Exception {
        var corpus = getClass().getResourceAsStream("/differential-corpus.txt");
        assertNotNull(corpus, "Regression corpus must be available");
        try (var reader = new BufferedReader(new InputStreamReader(corpus, StandardCharsets.UTF_8))) {
            int id = 0;
            for (String line : reader.lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) { continue; }
                String[] values = line.split(",");
                var model = new ModelDescription("Regression" + id++, ModelDescription.Kind.valueOf(values[0]), Integer.parseInt(values[1]),
                    Integer.parseInt(values[2]), Integer.parseInt(values[3]), Boolean.parseBoolean(values[4]), Boolean.parseBoolean(values[5]), values.length > 8 ? Integer.parseInt(values[8]) : 0);
                Path classes = compile(List.of(model), output.resolve("corpus-" + id));
                try (Workers workers = new Workers(classes)) { compare(workers, model, Integer.parseInt(values[6]), Long.parseLong(values[7]), false); }
            }
        }
    }

    private void compare(Workers workers, ModelDescription model, int operation, long value, boolean count) throws Exception {
        checkDeadline();
        Results results = workers.run(model, operation, value);
        String mismatch = mismatch(model, operation, results);
        if (count) { completed += model.comprehensiveOnly() ? 1 : 2; }
        if (mismatch == null) { return; }
        Path failure = output.resolve("failure-" + completed);
        Files.createDirectories(failure);
        Files.writeString(failure.resolve("original.java"), model.source());
        Files.writeString(failure.resolve("original-outcomes.txt"), results.toString());
        Properties original = configuration();
        original.setProperty("model", model.toString()); original.setProperty("operation", Integer.toString(operation));
        original.setProperty("input", Long.toString(value));
        try (var stream = Files.newOutputStream(failure.resolve("original.properties"))) { original.store(stream, "Original differential failure"); }
        ModelDescription minimized = model;
        long smallerValue = value;
        long shrinkDeadline = Math.min(deadline, System.nanoTime() + Duration.ofSeconds(45).toNanos());
        try {
        boolean changed = true;
        while (changed && System.nanoTime() < shrinkDeadline) {
            changed = false;
            for (ModelDescription candidate : minimized.smaller()) {
                Results candidateResults = evaluate(candidate, operation, smallerValue);
                if (mismatch(candidate, operation, candidateResults) != null && sameFailureShape(results, candidateResults)) {
                    minimized = candidate; results = candidateResults; changed = true; break;
                }
            }
        }
        for (long candidate : new long[]{0, 1, -1}) {
            if (System.nanoTime() >= shrinkDeadline) { break; }
            Results candidateResults = evaluate(minimized, operation, candidate);
            if (mismatch(minimized, operation, candidateResults) != null && sameFailureShape(results, candidateResults)) { smallerValue = candidate; results = candidateResults; break; }
        }
        } catch (Exception | AssertionError shrinkingFailure) {
            if (shrinkingFailure instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            Files.writeString(failure.resolve("shrink-error.txt"), shrinkingFailure.toString());
        }
        Files.writeString(failure.resolve("minimized.java"), minimized.source());
        Files.writeString(failure.resolve("outcomes.txt"), results.toString());
        Properties reproduction = configuration();
        reproduction.setProperty("model", minimized.toString());
        reproduction.setProperty("operation", Integer.toString(operation));
        reproduction.setProperty("input", Long.toString(smallerValue));
        reproduction.setProperty("corpusEntry", minimized.kind() + "," + minimized.minimum() + "," + minimized.maximum() + ","
            + minimized.width() + "," + minimized.group() + "," + minimized.permission() + "," + operation + "," + smallerValue + "," + minimized.reductions());
        try (var stream = Files.newOutputStream(failure.resolve("reproduction.properties"))) { reproduction.store(stream, "Reproducible differential failure"); }
        fail(mismatch + "; seed=" + seed + "; reproduction=" + failure);
    }

    private Results evaluate(ModelDescription model, int operation, long value) throws Exception {
        Path classes = compile(List.of(model), output.resolve("shrink-" + minimizedId++));
        try (Workers workers = new Workers(classes)) { return workers.run(model, operation, value); }
    }

    private static String mismatch(ModelDescription model, int operation, Results results) {
        if (!results.jakarta.equals(results.reference)) { return "Jakarta/reference mismatch"; }
        if (model.comprehensiveOnly()) { return null; }
        if (model.expectsCapability(operation)) {
            return results.generated.equals("CAPABILITY_ERROR") ? null : "Missing expected default capability error";
        }
        return results.generated.equals(results.reference) ? null : "Default/reference mismatch";
    }

    private static boolean sameFailureShape(Results original, Results candidate) {
        return outcomeShape(original.generated).equals(outcomeShape(candidate.generated))
            && outcomeShape(original.jakarta).equals(outcomeShape(candidate.jakarta))
            && outcomeShape(original.reference).equals(outcomeShape(candidate.reference));
    }

    private static String outcomeShape(String outcome) { return outcome.startsWith("[") ? "VIOLATIONS" : outcome; }

    private Path compile(List<ModelDescription> models, Path directory) throws Exception {
        checkDeadline();
        if (Files.exists(directory)) {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.delete(file); }
            }
        }
        Path sources = directory.resolve("sources");
        Path classes = directory.resolve("classes");
        Files.createDirectories(sources); Files.createDirectories(classes);
        List<String> arguments = new ArrayList<>(List.of("-encoding", "UTF-8", "-parameters", "-d", classes.toString(),
            "-classpath", System.getProperty("fuzz.defaultClasspath"), "-processorpath", System.getProperty("fuzz.processors")));
        for (ModelDescription model : models) {
            Path source = sources.resolve(model.name() + ".java");
            Files.writeString(source, model.source()); arguments.add(source.toString());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A JDK is required for generated model compilation");
        try (var diagnostics = Files.newOutputStream(directory.resolve("compiler.log"))) {
            assertEquals(0, compiler.run(null, diagnostics, diagnostics, arguments.toArray(String[]::new)),
                "Generated model compilation failed: " + directory.resolve("compiler.log"));
        }
        return classes;
    }

    private Properties configuration() {
        Properties properties = new Properties();
        properties.setProperty("seed", Long.toString(seed));
        properties.setProperty("requested", Integer.toString(requested));
        properties.setProperty("completed", Integer.toString(completed));
        properties.setProperty("reference", "Hibernate Validator 9.1.4.Final");
        properties.setProperty("authority", "Jakarta Validation 3.1");
        properties.setProperty("micronaut", "5.3.0-SNAPSHOT");
        properties.setProperty("locale", "en"); properties.setProperty("clock", "2026-01-15T12:00:00Z");
        properties.setProperty("parameterNames", "compiled"); properties.setProperty("EL", "Jakarta EL 6 / Micronaut EL");
        properties.setProperty("java", System.getProperty("java.version"));
        return properties;
    }

    private void writeStatus(String status) throws Exception {
        Properties configuration = configuration(); configuration.setProperty("status", status);
        try (var stream = Files.newOutputStream(output.resolve("campaign.properties"))) { configuration.store(stream, "Differential campaign status"); }
    }

    private void checkDeadline() {
        assertFalse(Thread.currentThread().isInterrupted(), "Differential campaign interrupted: INCOMPLETE");
        assertTrue(System.nanoTime() < deadline, "Differential campaign deadline reached: INCOMPLETE (" + completed + "/" + requested + ")");
    }

    private record Results(String generated, String jakarta, String reference) { }

    private final class Workers implements AutoCloseable {
        private final Worker generated;
        private final Worker jakarta;
        private final Worker reference;
        Workers(Path classes) throws Exception {
            generated = new Worker("default", classes);
            try { jakarta = new Worker("jakarta", classes); }
            catch (Throwable failure) { generated.close(); throw failure; }
            try { reference = new Worker("reference", classes); }
            catch (Throwable failure) { generated.close(); jakarta.close(); throw failure; }
        }
        Results run(ModelDescription model, int operation, long seed) throws Exception {
            return new Results(model.comprehensiveOnly() ? "OPTIONAL_PROFILE_ONLY" : generated.run(model, operation, seed), jakarta.run(model, operation, seed), reference.run(model, operation, seed));
        }
        @Override public void close() throws Exception { try { generated.close(); } finally { try { jakarta.close(); } finally { reference.close(); } } }
    }

    private final class Worker implements AutoCloseable {
        private final Process process;
        private final BufferedWriter requests;
        private final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<>();
        Worker(String profile, Path classes) throws Exception {
            String classpath = classes + java.io.File.pathSeparator + System.getProperty("fuzz." + profile + "Classpath");
            Path log = classes.getParent().resolve(profile + "-stderr.log");
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-cp", classpath, "io.micronaut.validation.differential.ValidationWorker", profile)
                .redirectError(log.toFile()).start();
            requests = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread.ofVirtual().start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = reader.readLine()) != null) { responses.add(line); }
                } catch (Exception failure) { responses.add("READER_ERROR:" + failure); }
                finally { responses.add("EXITED"); }
            });
            try {
                String ready = response("READY:");
                assertEquals("READY:" + profile, ready, "Worker did not start: " + profile + "; " + log);
            } catch (Throwable failure) { close(); throw failure; }
        }
        String run(ModelDescription model, int operation, long seed) throws Exception {
            requests.write("fuzz." + model.name() + "\t" + operation + "\t" + seed + "\n"); requests.flush();
            String outcome = response("OUTCOME:");
            assertTrue(outcome.startsWith("OUTCOME:"), "Worker exited: " + outcome);
            return new String(Base64.getDecoder().decode(outcome.substring(8)), StandardCharsets.UTF_8);
        }
        private String response(String prefix) throws Exception {
            while (true) {
                checkDeadline();
                String response = responses.poll(Math.min(Duration.ofSeconds(30).toNanos(), deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertNotNull(response, "Worker response timed out");
                if (response.startsWith(prefix) || response.equals("EXITED") || response.startsWith("READER_ERROR:")) { return response; }
            }
        }
        @Override public void close() throws Exception {
            try { requests.close(); }
            finally { if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); } }
        }
    }
}
