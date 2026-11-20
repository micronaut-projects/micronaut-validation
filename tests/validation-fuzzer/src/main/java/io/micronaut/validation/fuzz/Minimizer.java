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

import io.micronaut.validation.fuzz.Model.Anno;
import io.micronaut.validation.fuzz.Model.BeanSpec;
import io.micronaut.validation.fuzz.Model.CaseSpec;
import io.micronaut.validation.fuzz.Model.Kind;
import io.micronaut.validation.fuzz.Model.PropertySpec;
import io.micronaut.validation.fuzz.Model.TypeNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shrinks the case of a difference: greedily removes properties, constraints, cascades and group conversions while
 * the difference, by its signature, still shows.
 */
final class Minimizer {

    private static final int MAX_ATTEMPTS = 120;

    private final long seed;
    private final Path workDir;
    private int run;

    Minimizer(long seed, Path workDir) {
        this.seed = seed;
        this.workDir = workDir;
    }

    /**
     * The minimized case.
     *
     * @param sources The sources of its beans
     * @param instance The instance
     * @param difference The difference it still shows
     * @param attempts The number of compilations it took
     */
    record Minimized(String sources, String instance, Report.Difference difference, int attempts) {
    }

    Minimized minimize(Report.Difference example, int batchSize) throws Exception {
        String target = baseSignature(example.signature());
        List<String> accepted = new ArrayList<>();
        Report.Difference last = example;
        int attempts = 0;
        boolean progress = true;
        while (progress && attempts < MAX_ATTEMPTS) {
            progress = false;
            for (String candidate : candidates(fresh(example, batchSize, accepted))) {
                if (attempts >= MAX_ATTEMPTS) {
                    break;
                }
                List<String> trial = new ArrayList<>(accepted);
                trial.add(candidate);
                CaseSpec spec = fresh(example, batchSize, trial);
                if (spec == null) {
                    continue;
                }
                attempts++;
                Report.Difference found = reproduces(spec, example.batch(), target);
                if (found != null) {
                    accepted.add(candidate);
                    last = found;
                    progress = true;
                }
            }
        }
        CaseSpec spec = fresh(example, batchSize, accepted);
        StringBuilder sources = new StringBuilder();
        for (BeanSpec bean : spec.beans) {
            sources.append(SourceWriter.bean(bean)).append('\n');
        }
        return new Minimized(sources.toString().strip(), Report.stripMarkers(spec.instances.get(0)), last, attempts);
    }

    private Report.Difference reproduces(CaseSpec spec, int batch, String target) throws Exception {
        FuzzRunner runner = new FuzzRunner(seed, workDir, false);
        runner.runCases(batch, "m" + (run++), List.of(spec));
        return runner.report().find(d -> baseSignature(d.signature()).equals(target));
    }

    static String baseSignature(String signature) {
        return signature.replace(" [invalid-definition]", "").replace(" [unsupported-type]", "");
    }

    /**
     * Regenerates the case of the example with the reductions applied, or null if a reduction no longer applies.
     */
    private CaseSpec fresh(Report.Difference example, int batchSize, List<String> reductions) {
        FuzzRunner generatorRunner = new FuzzRunner(seed, workDir, false);
        CaseSpec spec = generatorRunner.generate(example.batch(), example.caseId() + 1).get(example.caseId());
        String instance = spec.instances.get(Math.max(0, example.instance()));
        spec.instances.clear();
        spec.instances.add(instance);
        for (String reduction : reductions) {
            if (!apply(spec, reduction)) {
                return null;
            }
        }
        return spec;
    }

    // ---------------------------------------------------------------- reductions

    private static List<String> candidates(CaseSpec spec) {
        List<String> result = new ArrayList<>();
        if (spec == null) {
            return result;
        }
        // whole properties first, then the details
        for (BeanSpec bean : spec.beans) {
            for (PropertySpec p : bean.properties) {
                result.add("prop|" + bean.name + "|" + p.name);
            }
        }
        for (BeanSpec bean : spec.beans) {
            for (Anno a : bean.classAnnos) {
                result.add("class|" + bean.name + "|" + a.render());
            }
            if (bean.redefinesDefaultGroup) {
                result.add("redefine|" + bean.name);
            }
            for (PropertySpec o : bean.overrides) {
                result.add("override|" + bean.name + "|" + o.name);
            }
            for (PropertySpec p : bean.properties) {
                for (Anno a : p.annos) {
                    result.add("anno|" + bean.name + "|" + p.name + "|" + a.render());
                }
                if (p.valid) {
                    result.add("valid|" + bean.name + "|" + p.name);
                }
                if (p.convertGroup != null) {
                    result.add("convert|" + bean.name + "|" + p.name);
                }
                collectArgs(bean, p, p.type, "", result);
            }
        }
        for (BeanSpec bean : spec.beans) {
            for (PropertySpec p : bean.properties) {
                for (Anno a : p.annos) {
                    if (!a.members().isEmpty()) {
                        for (String member : a.members().keySet()) {
                            result.add("member|" + bean.name + "|" + p.name + "|" + a.render() + "|" + member);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static void collectArgs(BeanSpec bean, PropertySpec p, TypeNode type, String path, List<String> result) {
        for (int i = 0; i < type.args.size(); i++) {
            TypeNode arg = type.args.get(i);
            String argPath = path + "/" + i;
            for (Anno a : arg.annos) {
                result.add("arg|" + bean.name + "|" + p.name + "|" + argPath + "|" + a.render());
            }
            if (arg.valid) {
                result.add("argvalid|" + bean.name + "|" + p.name + "|" + argPath);
            }
            collectArgs(bean, p, arg, argPath, result);
        }
    }

    private static boolean apply(CaseSpec spec, String reduction) {
        String[] r = reduction.split("\\|", -1);
        BeanSpec bean = spec.beans.stream().filter(b -> b.name.equals(r[1])).findFirst().orElse(null);
        if (bean == null) {
            return false;
        }
        switch (r[0]) {
            case "prop" -> {
                PropertySpec p = property(bean, r[2]);
                if (p == null) {
                    return false;
                }
                bean.properties.remove(p);
                bean.overrides.removeIf(o -> o.name.equals(p.name));
                for (BeanSpec other : spec.beans) {
                    other.overrides.removeIf(o -> o.name.equals(p.name) && other.parent == bean);
                }
                spec.instances.replaceAll(i -> Generator.withoutProperty(i, bean.name, p.name));
                prune(spec);
                return true;
            }
            case "class" -> {
                return bean.classAnnos.removeIf(a -> a.render().equals(r[2]));
            }
            case "redefine" -> {
                bean.redefinesDefaultGroup = false;
                return true;
            }
            case "override" -> {
                return bean.overrides.removeIf(o -> o.name.equals(r[2]));
            }
            case "anno" -> {
                PropertySpec p = property(bean, r[2]);
                return p != null && p.annos.removeIf(a -> a.render().equals(r[3]));
            }
            case "valid" -> {
                PropertySpec p = property(bean, r[2]);
                if (p == null || !p.valid) {
                    return false;
                }
                p.valid = false;
                p.convertGroup = null;
                return true;
            }
            case "convert" -> {
                PropertySpec p = property(bean, r[2]);
                if (p == null || p.convertGroup == null) {
                    return false;
                }
                p.convertGroup = null;
                return true;
            }
            case "arg", "argvalid" -> {
                PropertySpec p = property(bean, r[2]);
                TypeNode node = p == null ? null : node(p.type, r[3]);
                if (node == null) {
                    return false;
                }
                if (r[0].equals("argvalid")) {
                    boolean was = node.valid;
                    node.valid = false;
                    return was;
                }
                return node.annos.removeIf(a -> a.render().equals(r[4]));
            }
            case "member" -> {
                PropertySpec p = property(bean, r[2]);
                if (p == null) {
                    return false;
                }
                for (Anno a : p.annos) {
                    if (a.render().equals(r[3]) && a.members().containsKey(r[4])) {
                        a.members().remove(r[4]);
                        return true;
                    }
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private static PropertySpec property(BeanSpec bean, String name) {
        return bean.properties.stream().filter(p -> p.name.equals(name)).findFirst().orElse(null);
    }

    private static TypeNode node(TypeNode type, String path) {
        TypeNode node = type;
        for (String part : path.substring(1).split("/")) {
            int index = Integer.parseInt(part);
            if (index >= node.args.size()) {
                return null;
            }
            node = node.args.get(index);
        }
        return node;
    }

    /** Drops the beans the root no longer reaches. */
    private static void prune(CaseSpec spec) {
        Set<BeanSpec> reachable = new LinkedHashSet<>();
        reach(spec.root, reachable);
        spec.beans.removeIf(b -> !reachable.contains(b));
    }

    private static void reach(BeanSpec bean, Set<BeanSpec> reachable) {
        if (bean == null || !reachable.add(bean)) {
            return;
        }
        reach(bean.parent, reachable);
        for (PropertySpec p : bean.properties) {
            reachType(p.type, reachable);
        }
    }

    private static void reachType(TypeNode type, Set<BeanSpec> reachable) {
        if (type.kind == Kind.BEAN) {
            reach(type.bean, reachable);
        }
        for (TypeNode arg : type.args) {
            reachType(arg, reachable);
        }
    }
}
