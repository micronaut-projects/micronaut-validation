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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the differential fuzzer. The {@code fuzz} Gradle task passes its properties through.
 */
class ValidationFuzzTest {

    @Test
    void fuzz() throws Exception {
        long seed = Long.getLong("fuzz.seed", 1L);
        int batches = Integer.getInteger("fuzz.batches", 4);
        int batchSize = Integer.getInteger("fuzz.batchSize", 40);
        String replay = System.getProperty("fuzz.replay");
        boolean keep = Boolean.getBoolean("fuzz.keepSources") || replay != null;
        Path reportDir = Path.of(System.getProperty("fuzz.report", "build/fuzz-report"));
        Path work = Files.createDirectories(reportDir.resolve("work"));

        FuzzRunner runner = new FuzzRunner(seed, work, keep);
        Report report = runner.report();
        long start = System.currentTimeMillis();
        if (replay != null) {
            String[] parts = replay.split(":");
            runner.runBatch(Integer.parseInt(parts[0]), batchSize, parts.length > 1 ? Integer.parseInt(parts[1]) : -1);
            report.batches = 1;
            report.cases = parts.length > 1 ? 1 : batchSize;
        } else {
            for (int b = 0; b < batches; b++) {
                runner.runBatch(b, batchSize, -1);
                report.batches++;
                report.cases += batchSize;
                System.out.println("[fuzz] batch " + b + " done: " + report.differenceCount() + " differences in "
                    + report.signatureCount() + " signatures (" + (System.currentTimeMillis() - start) / 1000 + "s)");
            }
        }
        int minimize = Integer.getInteger("fuzz.minimize", 0);
        if (minimize > 0) {
            Minimizer minimizer = new Minimizer(seed, work);
            int done = 0;
            for (var group : report.coarseGroups().entrySet()) {
                if (done++ >= minimize) {
                    break;
                }
                Report.Difference example = group.getValue();
                try {
                    Minimizer.Minimized m = minimizer.minimize(example, batchSize);
                    Report.Difference d = m.difference();
                    report.minimized(group.getKey(), "From batch " + example.batch() + " case " + example.caseId()
                        + " (" + m.attempts() + " attempts), `" + d.check() + "`: " + d.signature() + "\n\n```java\n" + m.sources()
                        + "\n```\n\nInstance:\n\n```java\n" + m.instance() + "\n```\n\n```\n" + d.detail().strip() + "\n```\n");
                    System.out.println("[fuzz] minimized " + done + ": " + group.getKey());
                } catch (Exception e) {
                    report.minimized(group.getKey(), "Minimization failed: " + e);
                }
            }
        }
        String summary = report.write(reportDir);
        System.out.println(summary);
        System.out.println("[fuzz] report: " + reportDir.resolve("report.md").toAbsolutePath());
        Assertions.assertTrue(report.harnessErrors().isEmpty(), () -> "Harness errors: " + report.harnessErrors());
        if (Boolean.parseBoolean(System.getProperty("fuzz.failOnDifference", "false"))) {
            Assertions.assertEquals(0, report.differenceCount(), summary);
        }
    }
}
