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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Random differential fuzzing against Hibernate Validator.
 *
 * <p>Each iteration generates random models ({@link RandomBeanModel}), compiles them once with the Micronaut
 * processors, and runs every operation in three isolated JVMs: the default compile-time stack, the Jakarta bootstrap
 * stack and the reference implementation. A difference is reduced to a <em>signature</em> (constraint, declared
 * type, path shape and the fields that differ), so the campaign keeps going after a difference and reports each kind
 * once. New signatures are shrunk by removing model elements and saved with a corpus entry.</p>
 *
 * <p>System properties: {@code fuzz.seed}, {@code fuzz.randomModels}, {@code fuzz.limitMinutes},
 * {@code fuzz.reportOnly} (do not fail on differences) and {@code fuzz.output}.</p>
 */
class RandomDifferentialTest {
    private static final int BATCH = 20;
    private static final int INPUTS_PER_OPERATION = 3;

    private final long seed = Long.parseLong(System.getProperty("fuzz.seed", "631530"));
    private final int models = Integer.parseInt(System.getProperty("fuzz.randomModels", "60"));
    private final boolean reportOnly = Boolean.parseBoolean(System.getProperty("fuzz.reportOnly", "false"));
    private final Path output = Path.of(System.getProperty("fuzz.output", "build/differential")).resolve("random");
    private final long deadline = System.nanoTime() + Duration.ofMinutes(Long.parseLong(System.getProperty("fuzz.limitMinutes", "30"))).toNanos();

    private final Map<String, Finding> findings = new TreeMap<>();
    private final Set<String> knownSignatures = new TreeSet<>();
    private int comparisons;
    private int generated;
    private int compileFailures;
    private int scratch;
    /** Signatures with the declared type erased whose first occurrence was already shrunk. */
    private final Set<String> shrunkShapes = new TreeSet<>();

    /** One kind of difference. */
    private static final class Finding {
        final String signature;
        final Set<String> profiles = new TreeSet<>();
        int occurrences;
        String example;
        String reproduction;
        String reproductionSource;

        Finding(String signature) {
            this.signature = signature;
        }
    }

    @Test
    void randomModelsMatchTheReference() throws Exception {
        Files.createDirectories(output);
        loadKnown();
        replayCorpus();
        var random = new SplittableRandom(seed);
        int batch = 0;
        boolean incomplete = false;
        try {
            while (generated < models) {
                if (System.nanoTime() >= deadline) {
                    incomplete = true;
                    break;
                }
                List<RandomBeanModel> current = new ArrayList<>();
                for (int i = 0; i < BATCH && generated < models; i++, generated++) {
                    current.add(RandomBeanModel.of("Random" + batch + "_" + i, random.nextLong()));
                }
                runBatch(current, output.resolve("batch-" + (batch++ % 4)), random);
                writeReport(true);
            }
        } catch (IllegalStateException deadlineReached) {
            if (!String.valueOf(deadlineReached.getMessage()).contains("deadline")) {
                throw deadlineReached;
            }
            incomplete = true;
        }
        writeReport(incomplete);
        List<String> unexpected = findings.keySet().stream().filter(s -> !knownSignatures.contains(s)).toList();
        System.out.println("Random differential campaign: seed=" + seed + ", models=" + generated + ", comparisons=" + comparisons
            + ", signatures=" + findings.size() + ", unexpected=" + unexpected.size() + ", report=" + output.resolve("report.md"));
        if (!unexpected.isEmpty() && !reportOnly) {
            fail(unexpected.size() + " unexpected difference signature(s), see " + output.resolve("report.md") + ":\n  "
                + String.join("\n  ", unexpected));
        }
    }

    private void runBatch(List<RandomBeanModel> batch, Path directory, SplittableRandom random) throws Exception {
        List<RandomBeanModel> compiled = compileAll(batch, directory);
        if (compiled.isEmpty()) {
            return;
        }
        try (var workers = new ProviderWorkers(directory.resolve("classes"), deadline)) {
            for (RandomBeanModel model : compiled) {
                int operations = model.hasMethod() ? 5 : 3;
                for (int operation = 0; operation < operations; operation++) {
                    for (int i = 0; i < INPUTS_PER_OPERATION; i++) {
                        long input = random.nextInt(1000);
                        compare(workers, model, operation, input, true);
                    }
                }
            }
        }
    }

    /** Compiles the batch; when it fails, compiles models one by one to isolate those the processor rejects. */
    private List<RandomBeanModel> compileAll(List<RandomBeanModel> batch, Path directory) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        batch.forEach(model -> sources.put(model.name, model.source()));
        if (ProviderWorkers.compile(sources, directory) == null) {
            return batch;
        }
        List<RandomBeanModel> good = new ArrayList<>();
        for (RandomBeanModel model : batch) {
            Path single = output.resolve("scratch-compile");
            String log = ProviderWorkers.compile(Map.of(model.name, model.source()), single);
            if (log == null) {
                good.add(model);
            } else {
                compileFailures++;
                String first = log.lines().filter(line -> line.contains("error:")).findFirst().orElse(log.lines().findFirst().orElse(""));
                String signature = "COMPILE " + first.replaceAll("^.*?error: ", "").replaceAll("Random\\d+_\\d+", "Model")
                    .replaceAll("p\\d+", "pN").replaceAll("\\d+", "N");
                Finding finding = findings.computeIfAbsent(signature, Finding::new);
                finding.profiles.add("compiler");
                finding.occurrences++;
                if (finding.reproduction == null) {
                    Path failure = output.resolve("compile-" + findings.size());
                    Files.createDirectories(failure);
                    Files.writeString(failure.resolve(model.name + ".java"), model.source());
                    Files.writeString(failure.resolve("compiler.log"), log);
                    finding.reproduction = failure.toString();
                    finding.reproductionSource = model.source();
                    finding.example = log.lines().limit(15).reduce("", (a, b) -> a + b + "\n");
                }
            }
        }
        if (good.isEmpty()) {
            return good;
        }
        Map<String, String> goodSources = new LinkedHashMap<>();
        good.forEach(model -> goodSources.put(model.name, model.source()));
        String log = ProviderWorkers.compile(goodSources, directory);
        if (log != null) {
            throw new IllegalStateException("Models compile alone but not together:\n" + log);
        }
        return good;
    }

    private void compare(ProviderWorkers workers, RandomBeanModel model, int operation, long input, boolean shrink) throws Exception {
        Map<String, String> outcomes = workers.run(model.name, operation, input);
        comparisons++;
        Map<String, Set<String>> signatures = signatures(model, outcomes);
        for (var entry : signatures.entrySet()) {
            for (String signature : entry.getValue()) {
                Finding finding = findings.computeIfAbsent(signature, Finding::new);
                finding.profiles.add(entry.getKey());
                finding.occurrences++;
                if (finding.reproduction == null) {
                    finding.example = "operation=" + operation + " input=" + input + "\n" + describe(outcomes);
                    // shrinking costs a compile and three worker JVMs per round: shrink once per signature shape and keep
                    // the unminimized model for the same difference on another declared type
                    boolean firstOfShape = shrunkShapes.add(shape(signature));
                    RandomBeanModel minimized = shrink && firstOfShape ? shrink(model, operation, input, signature) : model;
                    saveReproduction(finding, minimized, operation, input);
                }
            }
        }
    }

    // ---------------------------------------------------------------- signatures

    /** Profile name to the difference signatures it shows against the reference. */
    private static Map<String, Set<String>> signatures(RandomBeanModel model, Map<String, String> outcomes) {
        Map<String, Set<String>> result = new TreeMap<>();
        String reference = outcomes.get("reference");
        for (var entry : outcomes.entrySet()) {
            if (entry.getKey().equals("reference")) {
                continue;
            }
            Set<String> differences = differences(model, entry.getValue(), reference);
            if (!differences.isEmpty()) {
                result.put(entry.getKey(), differences);
            }
        }
        return result;
    }

    private static Set<String> differences(RandomBeanModel model, String candidate, String reference) {
        Set<String> result = new TreeSet<>();
        String left = DifferentialCampaignTest.comparable(candidate);
        String right = DifferentialCampaignTest.comparable(reference);
        if (left.equals(right)) {
            return result;
        }
        boolean leftViolations = left.startsWith("[");
        boolean rightViolations = right.startsWith("[");
        if (!leftViolations || !rightViolations) {
            result.add("OUTCOME micronaut=" + shapeOf(left) + " reference=" + shapeOf(right)
                + (rightViolations ? " " + constraintsOf(model, right) : leftViolations ? " " + constraintsOf(model, left) : ""));
            return result;
        }
        List<Violation> mine = parse(left);
        List<Violation> theirs = parse(right);
        List<Violation> unmatchedTheirs = new ArrayList<>(theirs);
        List<Violation> unmatchedMine = new ArrayList<>();
        for (Violation violation : mine) {
            if (!unmatchedTheirs.remove(violation)) {
                unmatchedMine.add(violation);
            }
        }
        for (Violation violation : new ArrayList<>(unmatchedMine)) {
            Violation partner = unmatchedTheirs.stream().filter(v -> v.key().equals(violation.key())).findFirst().orElse(null);
            if (partner != null) {
                unmatchedMine.remove(violation);
                unmatchedTheirs.remove(partner);
                result.add("DIFF " + violation.type + " on " + violation.where(model) + ": " + violation.differingFields(partner));
            }
        }
        unmatchedMine.forEach(v -> result.add("EXTRA " + v.type + " on " + v.where(model)));
        unmatchedTheirs.forEach(v -> result.add("MISSING " + v.type + " on " + v.where(model)));
        return result;
    }

    /** The signature without the declared type: {@code DIFF NotNull on Optional<X> PROPERTY: message} to {@code DIFF NotNull on * PROPERTY: message}. */
    static String shape(String signature) {
        return signature.startsWith("OUTCOME ") ? signature
            : signature.replaceFirst(" on .*? ((?:[A-Z_]+>)*[A-Z_]+)(:|$)", " on * $1$2");
    }

    private static String shapeOf(String outcome) {
        if (outcome.startsWith("[")) {
            return outcome.equals("[]") ? "valid" : "violations";
        }
        return outcome.lines().findFirst().orElse(outcome);
    }

    private static String constraintsOf(RandomBeanModel model, String outcome) {
        Set<String> types = new TreeSet<>();
        parse(outcome).forEach(v -> types.add(v.type + "@" + v.where(model)));
        return types.toString();
    }

    private record Violation(String path, String template, String message, String invalid, String descriptor, String type) {
        String key() {
            return path + "|" + type;
        }

        String where(RandomBeanModel model) {
            List<String> kinds = new ArrayList<>();
            String leaf = null;
            String declared = "";
            List<String> arguments = List.of();
            for (String node : path.substring(1, path.length() - 1).split(", (?=[A-Z_]+:)")) {
                if (node.isEmpty()) {
                    continue;
                }
                String[] parts = node.split(":", 3);
                kinds.add(parts[0]);
                if (parts[0].equals("PROPERTY") && parts.length > 1) {
                    leaf = parts[1];
                } else if (parts[0].equals("METHOD") || parts[0].equals("CONSTRUCTOR")) {
                    arguments = argumentTypes(node);
                } else if (parts[0].equals("PARAMETER")) {
                    int at = node.lastIndexOf(":parameter=");
                    int index = at < 0 ? -1 : Integer.parseInt(node.substring(at + 11).replaceAll("\\D.*$", ""));
                    declared = index >= 0 && index < arguments.size() ? arguments.get(index) : "?";
                }
            }
            if (leaf != null) {
                declared = model.describeLeaf(leaf);
            }
            return declared + " " + String.join(">", kinds);
        }

        /** Simple names of the {@code arguments=[class:a.B, ...]} of a method or constructor node. */
        private static List<String> argumentTypes(String node) {
            int start = node.indexOf(":arguments=[");
            if (start < 0) {
                return List.of();
            }
            int end = node.indexOf(']', start);
            List<String> types = new ArrayList<>();
            for (String argument : node.substring(start + 12, end < 0 ? node.length() : end).split(", ")) {
                String type = argument.startsWith("class:") ? argument.substring(6) : argument;
                types.add(type.substring(type.lastIndexOf('.') + 1));
            }
            return types;
        }

        String differingFields(Violation other) {
            List<String> fields = new ArrayList<>();
            if (!template.equals(other.template)) {
                fields.add("template");
            }
            if (!message.equals(other.message)) {
                fields.add("message");
            }
            if (!invalid.equals(other.invalid)) {
                fields.add("invalidValue");
            }
            if (!descriptor.equals(other.descriptor)) {
                for (String part : new String[]{":attributes=", ":groups=", ":payload=", ":target=", ":unwrap=", ":single=", ":composition="}) {
                    if (!section(descriptor, part).equals(section(other.descriptor, part))) {
                        fields.add("descriptor" + part.replace(":", "").replace("=", ""));
                    }
                }
            }
            return String.join(",", fields);
        }

        private static String section(String descriptor, String part) {
            int start = descriptor.indexOf(part);
            if (start < 0) {
                return "";
            }
            // sections are ordered; cut at the next known section marker
            int next = descriptor.length();
            for (String marker : new String[]{":attributes=", ":groups=", ":payload=", ":target=", ":unwrap=", ":single=", ":composition="}) {
                int index = descriptor.indexOf(marker, start + part.length());
                if (index > 0 && index < next) {
                    next = index;
                }
            }
            return descriptor.substring(start, next);
        }
    }

    private static List<Violation> parse(String outcome) {
        List<Violation> violations = new ArrayList<>();
        String body = outcome.substring(1, outcome.length() - 1);
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int template = line.indexOf(";template=");
            int message = line.indexOf(";message=", template);
            int invalid = line.indexOf(";invalid=", message);
            int descriptor = line.indexOf(";descriptor=", invalid);
            String descriptorText = line.substring(descriptor + 12);
            int typeEnd = descriptorText.indexOf(":attributes=");
            String type = typeEnd < 0 ? descriptorText : descriptorText.substring(0, typeEnd);
            type = type.substring(type.lastIndexOf('.') + 1);
            violations.add(new Violation(line.substring(5, template), line.substring(template + 10, message),
                line.substring(message + 9, invalid), line.substring(invalid + 9, descriptor), descriptorText, type));
        }
        return violations;
    }

    // ---------------------------------------------------------------- shrinking

    /** Greedy element removal; each round compiles every single-removal candidate in one batch. */
    private RandomBeanModel shrink(RandomBeanModel original, int operation, long input, String signature) throws Exception {
        RandomBeanModel current = original;
        long shrinkDeadline = Math.min(deadline, System.nanoTime() + Duration.ofSeconds(90).toNanos());
        boolean changed = true;
        while (changed && System.nanoTime() < shrinkDeadline) {
            changed = false;
            List<Integer> ids = current.removableIds();
            if (ids.isEmpty()) {
                break;
            }
            Map<String, Integer> byName = new LinkedHashMap<>();
            Map<String, String> sources = new LinkedHashMap<>();
            for (int id : ids) {
                String name = "Shrink" + scratch++;
                byName.put(name, id);
                sources.put(name, current.without(id).renamed(name).source());
            }
            Path directory = output.resolve("scratch-shrink");
            if (ProviderWorkers.compile(sources, directory) != null) {
                // a removal produced code that does not compile (e.g. an unused method parameter type), compile one by one
                break;
            }
            Set<Integer> preserving = new LinkedHashSet<>();
            try (var workers = new ProviderWorkers(directory.resolve("classes"), deadline)) {
                for (var entry : byName.entrySet()) {
                    RandomBeanModel candidate = current.without(entry.getValue()).renamed(entry.getKey());
                    Map<String, String> outcomes = workers.run(entry.getKey(), operation, input);
                    if (signatures(candidate, outcomes).values().stream().anyMatch(s -> s.contains(signature))) {
                        preserving.add(entry.getValue());
                    }
                }
            }
            if (preserving.isEmpty()) {
                break;
            }
            RandomBeanModel combined = current.without(preserving);
            if (preserving.size() > 1 && !stillFails(combined, operation, input, signature)) {
                combined = current.without(preserving.iterator().next());
            }
            current = combined;
            changed = true;
        }
        return current.renamed(original.name);
    }

    private boolean stillFails(RandomBeanModel model, int operation, long input, String signature) throws Exception {
        String name = "Shrink" + scratch++;
        RandomBeanModel candidate = model.renamed(name);
        Path directory = output.resolve("scratch-verify");
        if (ProviderWorkers.compile(Map.of(name, candidate.source()), directory) != null) {
            return false;
        }
        try (var workers = new ProviderWorkers(directory.resolve("classes"), deadline)) {
            return signatures(candidate, workers.run(name, operation, input)).values().stream().anyMatch(s -> s.contains(signature));
        }
    }

    private void saveReproduction(Finding finding, RandomBeanModel model, int operation, long input) throws Exception {
        Path failure = output.resolve("finding-" + String.format("%03d", findings.size()));
        Files.createDirectories(failure);
        Files.writeString(failure.resolve(model.name + ".java"), model.source());
        Files.writeString(failure.resolve("signature.txt"), finding.signature + "\n");
        Files.writeString(failure.resolve("example.txt"), finding.example);
        Files.writeString(failure.resolve("corpus.txt"), model.corpusEntry() + "|" + operation + "|" + input + "\n");
        finding.reproduction = failure.toString();
        finding.reproductionSource = model.source();
    }

    private static String describe(Map<String, String> outcomes) {
        StringBuilder text = new StringBuilder();
        outcomes.forEach((profile, outcome) -> text.append("--- ").append(profile).append('\n').append(decodeText(outcome)).append('\n'));
        return text.toString();
    }

    /** Turns the hex code point lists of the normalized outcome back into readable text for reports. */
    static String decodeText(String outcome) {
        var matcher = java.util.regex.Pattern.compile("text:\\[([0-9a-f, ]*)]").matcher(outcome);
        StringBuilder text = new StringBuilder();
        while (matcher.find()) {
            StringBuilder decoded = new StringBuilder("\"");
            for (String codePoint : matcher.group(1).split(", ")) {
                if (!codePoint.isEmpty()) {
                    decoded.appendCodePoint(Integer.parseInt(codePoint, 16));
                }
            }
            matcher.appendReplacement(text, java.util.regex.Matcher.quoteReplacement(decoded.append('"').toString().replace("\n", "\\n")));
        }
        matcher.appendTail(text);
        return text.toString();
    }

    // ---------------------------------------------------------------- corpus and report

    private void loadKnown() throws Exception {
        var stream = getClass().getResourceAsStream("/random-known-differences.txt");
        if (stream == null) {
            return;
        }
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            reader.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).forEach(knownSignatures::add);
        }
    }

    private void replayCorpus() throws Exception {
        var stream = getClass().getResourceAsStream("/random-corpus.txt");
        if (stream == null) {
            return;
        }
        List<String[]> entries = new ArrayList<>();
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            reader.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).forEach(line -> entries.add(line.split("\\|")));
        }
        if (entries.isEmpty()) {
            return;
        }
        List<RandomBeanModel> corpus = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            corpus.add(RandomBeanModel.fromCorpus("Corpus" + i, entries.get(i)[0]));
        }
        Path directory = output.resolve("corpus");
        List<RandomBeanModel> compiled = compileAll(corpus, directory);
        try (var workers = new ProviderWorkers(directory.resolve("classes"), deadline)) {
            for (int i = 0; i < corpus.size(); i++) {
                if (compiled.contains(corpus.get(i))) {
                    compare(workers, corpus.get(i), Integer.parseInt(entries.get(i)[1]), Long.parseLong(entries.get(i)[2]), false);
                }
            }
        }
    }

    private void writeReport(boolean incomplete) throws Exception {
        StringBuilder report = new StringBuilder("# Random differential campaign\n\n");
        report.append("- status: ").append(incomplete ? "INCOMPLETE (deadline)" : "COMPLETE").append('\n');
        report.append("- seed: ").append(seed).append('\n');
        report.append("- models: ").append(generated).append(", comparisons: ").append(comparisons)
            .append(", models rejected by the compiler: ").append(compileFailures).append('\n');
        report.append("- reference: Hibernate Validator; profiles: default (compile-time), jakarta (bootstrap + reflection)\n");
        report.append("- signatures: ").append(findings.size()).append("\n\n");
        report.append("| # | signature | profiles | occurrences | known | reproduction |\n|---|---|---|---|---|---|\n");
        int index = 0;
        for (Finding finding : findings.values()) {
            report.append("| ").append(++index).append(" | `").append(finding.signature.replace("|", "\\|")).append("` | ")
                .append(String.join(", ", finding.profiles)).append(" | ").append(finding.occurrences).append(" | ")
                .append(knownSignatures.contains(finding.signature) ? "yes" : "no").append(" | ")
                .append(finding.reproduction == null ? "" : output.relativize(Path.of(finding.reproduction))).append(" |\n");
        }
        index = 0;
        for (Finding finding : findings.values()) {
            report.append("\n## ").append(++index).append(". ").append(finding.signature).append("\n\n");
            if (finding.example != null) {
                report.append("```\n").append(finding.example.strip()).append("\n```\n\n");
            }
            if (finding.reproductionSource != null) {
                report.append("<details><summary>minimized model</summary>\n\n```java\n").append(finding.reproductionSource.strip())
                    .append("\n```\n</details>\n");
            }
        }
        Files.writeString(output.resolve("report.md"), report.toString());
    }
}
