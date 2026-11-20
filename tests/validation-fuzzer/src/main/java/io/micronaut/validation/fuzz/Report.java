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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The differences found by a fuzzing run, grouped by signature.
 */
public final class Report {

    private static final int EXAMPLES = 3;

    int instances;
    int checks;
    int cases;
    int batches;

    private final long seed;
    private final Map<String, List<Difference>> bySignature = new LinkedHashMap<>();
    private final Map<String, Set<String>> casesBySignature = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final List<String> harnessErrors = new ArrayList<>();
    private final Map<String, String> minimized = new LinkedHashMap<>();

    Report(long seed) {
        this.seed = seed;
    }

    /**
     * A difference between the implementations.
     *
     * @param kind The kind
     * @param signature What groups similar differences
     * @param batch The batch
     * @param caseId The case
     * @param instance The instance, or -1
     * @param check The call that differed
     * @param detail The details
     * @param sources The sources of the case
     * @param instanceSource The expression building the instance
     */
    public record Difference(String kind, String signature, int batch, int caseId, int instance, String check,
                             String detail, String sources, String instanceSource) {
    }

    synchronized void add(Difference d) {
        counts.merge(d.signature(), 1, Integer::sum);
        casesBySignature.computeIfAbsent(d.signature(), k -> new LinkedHashSet<>()).add(d.batch() + ":" + d.caseId());
        List<Difference> examples = bySignature.computeIfAbsent(d.signature(), k -> new ArrayList<>());
        if (examples.size() < EXAMPLES && examples.stream().noneMatch(e -> e.batch() == d.batch() && e.caseId() == d.caseId())) {
            examples.add(d);
        }
    }

    /**
     * @param predicate The predicate
     * @return The first example matching
     */
    synchronized Difference find(java.util.function.Predicate<Difference> predicate) {
        return bySignature.values().stream().flatMap(List::stream).filter(predicate).findFirst().orElse(null);
    }

    static String stripMarkers(String instance) {
        return instance == null ? null : instance.replaceAll("/\\*</?[^*]*>\\*/", "");
    }

    /**
     * The signatures grouped coarsely: what usually shares a root cause.
     *
     * @return The first example of each coarse group, by group, the most frequent first
     */
    synchronized Map<String, Difference> coarseGroups() {
        Map<String, Integer> casesByGroup = new LinkedHashMap<>();
        Map<String, Difference> first = new LinkedHashMap<>();
        for (String s : sortedSignatures()) {
            String group = coarse(s);
            casesByGroup.merge(group, casesBySignature.get(s).size(), Integer::sum);
            first.putIfAbsent(group, bySignature.get(s).get(0));
        }
        Map<String, Difference> result = new LinkedHashMap<>();
        casesByGroup.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
            .forEach(e -> result.put(e.getKey(), first.get(e.getKey())));
        return result;
    }

    static String coarse(String signature) {
        String k = Minimizer.baseSignature(signature);
        k = k.replaceAll(" on (record|field-bean|getter-bean|class of)\\S*( \\S+)?", "");
        k = k.replaceAll("(declared|type-argument|override)( @Valid)?( @ConvertGroup)?$", "$1");
        k = k.replaceAll("\\[k]|\\[i]", "");
        k = k.replaceAll("^(EXTRA_IN_MICRONAUT|MISSING_IN_MICRONAUT|ONLY_\\w+|EXCEPTION_TYPE) \\w+ ", "$1 ");
        k = k.replaceAll("^(PATH_STRING) .*", "$1");
        k = k.replaceAll("^(DETAIL [\\w+]+( \\(EL template\\))?) .*", "$1");
        k = k.replaceAll("(class-level) .*", "$1");
        return k;
    }

    synchronized void minimized(String group, String text) {
        minimized.put(group, text);
    }

    synchronized void harnessError(int batch, String message) {
        harnessErrors.add("batch " + batch + ": " + message);
        System.err.println("[fuzz] harness error in batch " + batch + ": " + message.lines().findFirst().orElse(""));
    }

    /**
     * @return The number of distinct difference signatures
     */
    public int signatureCount() {
        return counts.size();
    }

    /**
     * @return The total number of differences
     */
    public int differenceCount() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * @return The harness errors
     */
    public List<String> harnessErrors() {
        return harnessErrors;
    }

    /**
     * @return The signatures by number of cases showing them
     */
    public Map<String, Integer> casesPerSignature() {
        Map<String, Integer> result = new LinkedHashMap<>();
        sortedSignatures().forEach(s -> result.put(s, casesBySignature.get(s).size()));
        return result;
    }

    private List<String> sortedSignatures() {
        return counts.keySet().stream()
            .sorted(Comparator.comparing((String s) -> s.split(" ")[0])
                .thenComparing(s -> -casesBySignature.get(s).size())
                .thenComparing(s -> s))
            .toList();
    }

    /**
     * Writes the report as Markdown and returns the summary.
     *
     * @param dir The directory
     * @return The summary
     * @throws IOException If writing fails
     */
    public String write(Path dir) throws IOException {
        Files.createDirectories(dir);
        StringBuilder summary = new StringBuilder();
        summary.append("# Differential validation fuzzing report\n\n");
        summary.append("Micronaut Validation (application context validator) vs Hibernate Validator.\n\n");
        summary.append("- seed: `").append(seed).append("`\n");
        summary.append("- batches: ").append(batches).append(", cases: ").append(cases).append(", instances: ").append(instances)
            .append(", checks: ").append(checks).append('\n');
        summary.append("- differences: ").append(differenceCount()).append(" in ").append(signatureCount()).append(" signatures\n");
        summary.append("- harness errors: ").append(harnessErrors.size()).append("\n\n");
        summary.append("Replay one case with `-Pfuzz.seed=").append(seed).append(" -Pfuzz.replay=<batch>:<case>`.\n\n");
        summary.append("| # | cases | hits | signature |\n|---|---|---|---|\n");
        int i = 0;
        List<String> sorted = sortedSignatures();
        for (String s : sorted) {
            summary.append("| ").append(++i).append(" | ").append(casesBySignature.get(s).size()).append(" | ").append(counts.get(s))
                .append(" | ").append(s.replace("|", "\\|")).append(" |\n");
        }
        StringBuilder full = new StringBuilder(summary);
        if (!minimized.isEmpty()) {
            full.append("\n## Minimized reproducers\n\nThe first example of each coarse group of signatures, shrunk while it still shows the difference.\n");
            int m = 0;
            for (Map.Entry<String, String> e : minimized.entrySet()) {
                full.append("\n### M").append(++m).append(". ").append(e.getKey()).append("\n\n").append(e.getValue()).append('\n');
            }
        }
        full.append("\n## Examples\n");
        i = 0;
        for (String s : sorted) {
            full.append("\n### ").append(++i).append(". ").append(s).append("\n\n");
            full.append("Cases (batch:case): ").append(String.join(", ", casesBySignature.get(s).stream().limit(20).toList()))
                .append(casesBySignature.get(s).size() > 20 ? ", ..." : "").append("\n");
            for (Difference d : bySignature.get(s)) {
                full.append("\n#### batch ").append(d.batch()).append(" case ").append(d.caseId());
                if (d.instance() >= 0) {
                    full.append(" instance ").append(d.instance());
                }
                full.append(" — `").append(d.check()).append("`\n\n```\n").append(d.detail().strip()).append("\n```\n\n");
                full.append("```java\n").append(d.sources().strip()).append("\n```\n");
                if (d.instanceSource() != null) {
                    full.append("\nInstance:\n\n```java\n").append(stripMarkers(d.instanceSource())).append("\n```\n");
                }
            }
        }
        if (!harnessErrors.isEmpty()) {
            full.append("\n## Harness errors\n\n");
            for (String e : harnessErrors) {
                full.append("```\n").append(e.length() > 6000 ? e.substring(0, 6000) + "\n..." : e).append("\n```\n\n");
            }
        }
        Files.writeString(dir.resolve("report.md"), full.toString());
        return summary.toString();
    }
}
