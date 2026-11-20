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

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.i18n.ResourceBundleMessageSource;
import io.micronaut.validation.fuzz.Canonical.Violation;
import io.micronaut.validation.fuzz.Model.Anno;
import io.micronaut.validation.fuzz.Model.BeanSpec;
import io.micronaut.validation.fuzz.Model.CaseSpec;
import io.micronaut.validation.fuzz.Model.PropertySpec;
import io.micronaut.validation.fuzz.Model.TypeNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.groups.Default;
import org.hibernate.validator.HibernateValidator;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Generates batches of cases, compiles them with the Micronaut annotation processors and validates their instances
 * with Micronaut Validation and Hibernate Validator, recording every difference.
 */
public final class FuzzRunner {

    private final long seed;
    private final Path workDir;
    private final boolean keepSources;
    private final Report report;

    /**
     * @param seed The seed
     * @param workDir The directory sources and classes are generated in
     * @param keepSources Whether the generated sources of a batch are kept
     */
    public FuzzRunner(long seed, Path workDir, boolean keepSources) {
        this.seed = seed;
        this.workDir = workDir;
        this.keepSources = keepSources;
        this.report = new Report(seed);
    }

    /**
     * @return The report
     */
    public Report report() {
        return report;
    }

    /**
     * Runs a batch.
     *
     * @param batch The batch index
     * @param size The number of cases
     * @param onlyCase The only case to keep, or -1
     * @throws Exception If the harness fails
     */
    public void runBatch(int batch, int size, int onlyCase) throws Exception {
        List<CaseSpec> cases = new ArrayList<>();
        for (CaseSpec spec : generate(batch, size)) {
            if (onlyCase < 0 || onlyCase == spec.id) {
                cases.add(spec);
            }
        }
        runCases(batch, "b" + batch, cases);
    }

    /**
     * Generates the cases of a batch: the same seed and batch always give the same cases.
     *
     * @param batch The batch
     * @param size The number of cases
     * @return The cases
     */
    List<CaseSpec> generate(int batch, int size) {
        Random random = new Random(seed * 1_000_003L + batch);
        Generator generator = new Generator(random);
        List<CaseSpec> cases = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            cases.add(generator.generateCase(i));
        }
        return cases;
    }

    /**
     * Compiles and validates cases.
     *
     * @param batch The batch the cases come from
     * @param name The name of the package and directory to generate them in
     * @param cases The cases
     * @throws Exception If the harness fails
     */
    void runCases(int batch, String name, List<CaseSpec> cases) throws Exception {
        String pkg = "fuzz." + name;
        Path batchDir = workDir.resolve(name);
        Path src = batchDir.resolve("src");
        Path classes = batchDir.resolve("classes");
        deleteRecursively(batchDir);
        Files.createDirectories(src);
        Files.createDirectories(classes);

        Map<String, String> support = SourceWriter.supportSources(pkg);
        Map<Integer, Map<String, String>> caseFiles = new LinkedHashMap<>();
        for (CaseSpec spec : cases) {
            caseFiles.put(spec.id, SourceWriter.caseSources(pkg, spec));
        }
        Files.writeString(classes.resolve("ValidationMessages.properties"), SourceWriter.messageBundle());

        // compile, dropping the cases that do not compile
        List<CaseSpec> remaining = new ArrayList<>(cases);
        CorpusCompiler.Result result = null;
        for (int attempt = 0; attempt < 6 && !remaining.isEmpty(); attempt++) {
            deleteRecursively(classes);
            Files.createDirectories(classes);
            Files.writeString(classes.resolve("ValidationMessages.properties"), SourceWriter.messageBundle());
            List<Path> files = new ArrayList<>();
            write(src, support, files);
            for (CaseSpec spec : remaining) {
                write(src, caseFiles.get(spec.id), files);
            }
            String fixtures = SourceWriter.fixtures(pkg, remaining);
            Path fixturesPath = src.resolve(pkg.replace('.', '/')).resolve("Fixtures.java");
            Files.writeString(fixturesPath, fixtures);
            files.add(fixturesPath);
            result = CorpusCompiler.compile(files, classes, true);
            if (result.success()) {
                break;
            }
            Set<Integer> failing = failingCases(result, fixtures, remaining);
            if (failing.isEmpty()) {
                report.harnessError(batch, "compilation failed outside the cases:\n" + result.output());
                return;
            }
            for (Integer id : failing) {
                CaseSpec spec = remaining.stream().filter(c -> c.id == id).findFirst().orElseThrow();
                classifyCompileFailure(batch, spec, pkg, support, caseFiles.get(id), result);
            }
            remaining.removeIf(c -> failing.contains(c.id));
        }
        if (result == null || !result.success() || remaining.isEmpty()) {
            return;
        }
        Map<String, BeanSpec> beans = new LinkedHashMap<>();
        Map<Integer, CaseSpec> byId = new LinkedHashMap<>();
        for (CaseSpec spec : remaining) {
            byId.put(spec.id, spec);
            for (BeanSpec bean : spec.beans) {
                beans.put(bean.name, bean);
            }
        }
        validate(batch, pkg, classes, byId, beans);
        if (!keepSources) {
            deleteRecursively(batchDir);
        }
    }

    private void classifyCompileFailure(int batch, CaseSpec spec, String pkg, Map<String, String> support,
                                        Map<String, String> sources, CorpusCompiler.Result batchResult) throws IOException {
        // the case alone, compiled without the Micronaut processors: if javac accepts it, a processor rejected it
        Path dir = workDir.resolve("b" + batch + "-c" + spec.id);
        deleteRecursively(dir);
        List<Path> files = new ArrayList<>();
        write(dir.resolve("src"), support, files);
        write(dir.resolve("src"), sources, files);
        CorpusCompiler.Result plain = CorpusCompiler.compile(files, dir.resolve("plain"), false);
        CorpusCompiler.Result processed = plain.success() ? CorpusCompiler.compile(files, dir.resolve("processed"), true) : plain;
        deleteRecursively(dir);
        String flags = flags(spec);
        if (!plain.success()) {
            report.harnessError(batch, "generated case " + spec.id + " is not valid Java:\n" + plain.output() + "\n" + sourcesOf(spec, pkg));
            return;
        }
        if (processed.success()) {
            // only fails together with the instances
            report.harnessError(batch, "fixtures of case " + spec.id + " do not compile:\n" + batchResult.output());
            return;
        }
        String message = processed.errors().values().stream().flatMap(List::stream).map(CorpusCompiler.Diag::message)
            .findFirst().orElse(processed.output());
        report.add(new Report.Difference("COMPILE_REJECTED", "COMPILE_REJECTED " + normalize(firstLine(message)) + flags,
            batch, spec.id, -1, "compile", processed.output(), sourcesOf(spec, pkg), null));
    }

    private static String firstLine(String s) {
        return s.lines().findFirst().orElse(s);
    }

    private Set<Integer> failingCases(CorpusCompiler.Result result, String fixtures, List<CaseSpec> cases) {
        Set<Integer> failing = new LinkedHashSet<>();
        List<String> fixtureLines = fixtures.lines().toList();
        for (Map.Entry<String, List<CorpusCompiler.Diag>> e : result.errors().entrySet()) {
            String file = e.getKey();
            if (file.startsWith("C") && file.contains("_B")) {
                failing.add(Integer.parseInt(file.substring(1, file.indexOf("_B"))));
            } else if (file.equals("Fixtures.java")) {
                for (CorpusCompiler.Diag d : e.getValue()) {
                    for (int line = (int) d.line() - 1; line >= 0 && line < fixtureLines.size(); line--) {
                        String text = fixtureLines.get(line);
                        int at = text.indexOf("static Object instance");
                        if (at >= 0) {
                            String id = text.substring(at + "static Object instance".length(), text.indexOf('_', at));
                            failing.add(Integer.parseInt(id));
                            break;
                        }
                    }
                }
            } else if (file.equals("<compiler>") || file.equals("<none>")) {
                // a processor crash or a diagnostic without source: bisect by dropping half the cases
                if (cases.size() == 1) {
                    failing.add(cases.get(0).id);
                }
            }
        }
        if (failing.isEmpty() && result.errors().containsKey("<compiler>") && cases.size() > 1) {
            // isolate a crash case by case
            for (CaseSpec spec : cases) {
                failing.add(spec.id);
            }
        }
        return failing;
    }

    // ---------------------------------------------------------------- validation

    private void validate(int batch, String pkg, Path classes, Map<Integer, CaseSpec> cases, Map<String, BeanSpec> beans) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, FuzzRunner.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            try (ApplicationContext context = ApplicationContext.builder().classLoader(loader).deduceEnvironment(false)
                     // how a Micronaut application supplies the ValidationMessages bundle the reference implementation reads
                     .singletons(new ResourceBundleMessageSource("ValidationMessages", Locale.US))
                     .start();
                 ValidatorFactory hvFactory = Validation.byProvider(HibernateValidator.class).configure()
                     .externalClassLoader(loader).buildValidatorFactory()) {
                Validator mn = context.getBean(io.micronaut.validation.validator.Validator.class);
                Validator hv = hvFactory.getValidator();
                Class<?> g1 = loader.loadClass(pkg + ".G1");
                Class<?> g2 = loader.loadClass(pkg + ".G2");
                Class<?> g3 = loader.loadClass(pkg + ".G3");
                Class<?> seq = loader.loadClass(pkg + ".Seq");
                List<Class<?>[]> groupSets = List.of(
                    new Class<?>[0], new Class<?>[] {g1}, new Class<?>[] {g2}, new Class<?>[] {g3}, new Class<?>[] {seq},
                    new Class<?>[] {Default.class, g3});
                Context ctx = new Context(batch, pkg, cases, beans);
                // metadata
                for (CaseSpec spec : cases.values()) {
                    for (BeanSpec bean : spec.beans) {
                        Class<?> type = loader.loadClass(pkg + "." + bean.name);
                        compareMetadata(ctx, spec, type, mn, hv);
                    }
                }
                @SuppressWarnings("unchecked")
                List<Object[]> instances = (List<Object[]>) loader.loadClass(pkg + ".Fixtures").getMethod("cases").invoke(null);
                for (Object[] row : instances) {
                    int caseId = (Integer) row[0];
                    int instance = (Integer) row[1];
                    Object bean = row[2];
                    CaseSpec spec = cases.get(caseId);
                    report.instances++;
                    for (Class<?>[] groups : groupSets) {
                        compareViolations(ctx, spec, instance, "validate" + Canonical.groups(groups),
                            () -> mn.validate(bean, groups), () -> hv.validate(bean, groups));
                    }
                    for (PropertySpec property : spec.root.allProperties()) {
                        compareViolations(ctx, spec, instance, "validateProperty(" + property.name + ")",
                            () -> mn.validateProperty(bean, property.name), () -> hv.validateProperty(bean, property.name));
                        Object value = read(bean, property.name);
                        @SuppressWarnings("unchecked")
                        Class<Object> type = (Class<Object>) bean.getClass();
                        compareViolations(ctx, spec, instance, "validateValue(" + property.name + ")",
                            () -> mn.validateValue(type, property.name, value), () -> hv.validateValue(type, property.name, value));
                    }
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static Object read(Object bean, String name) {
        Class<?> type = bean.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(bean);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("No field " + name + " in " + bean.getClass());
    }

    private record Context(int batch, String pkg, Map<Integer, CaseSpec> cases, Map<String, BeanSpec> beans) {
    }

    private void compareMetadata(Context ctx, CaseSpec spec, Class<?> type, Validator mn, Validator hv) {
        report.checks++;
        String check = "getConstraintsForClass(" + type.getSimpleName() + ")";
        Outcome<jakarta.validation.metadata.BeanDescriptor> m = Outcome.of(() -> mn.getConstraintsForClass(type));
        Outcome<jakarta.validation.metadata.BeanDescriptor> h = Outcome.of(() -> hv.getConstraintsForClass(type));
        Outcome<List<String>> ml = m.error != null ? new Outcome<>(null, m.error) : Outcome.of(() -> Canonical.beanDescriptor(m.value));
        Outcome<List<String>> hl = h.error != null ? new Outcome<>(null, h.error) : Outcome.of(() -> Canonical.beanDescriptor(h.value));
        if (exceptionDiff(ctx, spec, -1, check, "metadata", ml, hl)) {
            return;
        }
        BeanSpec bean = ctx.beans.get(type.getSimpleName());
        String sources = sourcesOf(spec, ctx.pkg);
        if (ml.value.equals(hl.value)) {
            List<String> rawM = Canonical.beanDescriptor(m.value, false);
            List<String> rawH = Canonical.beanDescriptor(h.value, false);
            if (!rawM.equals(rawH)) {
                report.add(new Report.Difference("METADATA", "METADATA attribute class arrays (groups/payload) not in declaration order",
                    ctx.batch, spec.id, -1, check, "Micronaut:\n" + String.join("\n", rawM) + "\nHibernate:\n" + String.join("\n", rawH), sources, null));
            }
            return;
        }
        // constraints of the class and properties
        Map<String, List<String>> mc = Canonical.constraintsByElement(m.value);
        Map<String, List<String>> hc = Canonical.constraintsByElement(h.value);
        Set<String> keys = new LinkedHashSet<>(hc.keySet());
        keys.addAll(mc.keySet());
        for (String key : keys) {
            List<String> a = mc.get(key);
            List<String> b = hc.get(key);
            String location = location(bean, key);
            if (a == null || b == null) {
                report.add(new Report.Difference("METADATA", "METADATA constrained property " + (a == null ? "missing in Micronaut" : "only in Micronaut")
                    + location + flags(spec), ctx.batch, spec.id, -1, check, key + "\nMicronaut: " + a + "\nHibernate: " + b, sources, null));
                continue;
            }
            if (a.equals(b)) {
                continue;
            }
            List<String> onlyA = new ArrayList<>(a);
            b.forEach(onlyA::remove);
            List<String> onlyB = new ArrayList<>(b);
            a.forEach(onlyB::remove);
            String what;
            if (onlyB.isEmpty() && b.containsAll(onlyA)) {
                what = "duplicated constraint descriptors in Micronaut";
            } else if (names(onlyA).equals(names(onlyB))) {
                what = "constraint descriptor " + differingDescriptorParts(onlyA, onlyB) + " differ " + names(onlyA);
            } else {
                what = (onlyB.isEmpty() ? "" : "missing in Micronaut " + names(onlyB) + " ") + (onlyA.isEmpty() ? "" : "only in Micronaut " + names(onlyA));
            }
            report.add(new Report.Difference("METADATA", "METADATA " + what.trim() + location + flags(spec), ctx.batch, spec.id, -1, check,
                key + "\nMicronaut: " + a + "\nHibernate: " + b, sources, null));
        }
        Map<String, String> mk = Canonical.containersByProperty(m.value);
        Map<String, String> hk = Canonical.containersByProperty(h.value);
        for (String key : hk.keySet()) {
            if (mk.containsKey(key) && !mk.get(key).equals(hk.get(key))) {
                report.add(new Report.Difference("METADATA", "METADATA container element descriptors differ" + location(bean, key) + flags(spec),
                    ctx.batch, spec.id, -1, check, key + "\nMicronaut: " + mk.get(key) + "\nHibernate: " + hk.get(key), sources, null));
            }
        }
        // the rest: bean summary, types, cascading, group conversions
        Map<String, String> mnLines = keyed(ml.value);
        Map<String, String> hvLines = keyed(hl.value);
        for (String key : hvLines.keySet()) {
            String a = mnLines.get(key);
            String b = hvLines.get(key);
            if (a == null || Objects.equals(a, b)) {
                continue;
            }
            String segments = differingSegments(a, b).replace("constraints+", "").replace("+containers", "").replace("containers+", "");
            if (segments.equals("constraints differ") || segments.equals("containers differ") || segments.equals("differs")) {
                continue;
            }
            String where = key.startsWith("property ") ? location(bean, key.substring("property ".length())) : "";
            report.add(new Report.Difference("METADATA", "METADATA " + (key.startsWith("property") ? "property " : key + " ") + segments + where + flags(spec),
                ctx.batch, spec.id, -1, check, "Micronaut:  " + a + "\nHibernate:  " + b, sources, null));
        }
    }

    private static String location(BeanSpec bean, String property) {
        if (bean == null) {
            return "";
        }
        if (property.equals("<class>")) {
            return " on class of " + flavor(bean);
        }
        PropertySpec p = findProperty(bean, property);
        return p == null ? " on " + flavor(bean) : " on " + flavor(bean) + " " + p.type.describe();
    }

    private static List<String> names(List<String> descriptors) {
        return descriptors.stream().map(d -> d.substring(0, d.indexOf('{'))).sorted().toList();
    }

    private static String differingDescriptorParts(List<String> a, List<String> b) {
        Set<String> parts = new java.util.TreeSet<>();
        String[] keys = {"groups=", "payload=", "single=", "appliesTo=", "template=", "attributes=", "composing="};
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            for (String key : keys) {
                if (!Objects.equals(part(a.get(i), key), part(b.get(i), key))) {
                    parts.add(key.replace("=", ""));
                }
            }
        }
        return parts.isEmpty() ? "descriptor" : String.join("+", parts);
    }

    private static String part(String descriptor, String key) {
        int at = descriptor.indexOf(key);
        if (at < 0) {
            return null;
        }
        int end = descriptor.indexOf(", ", at);
        if (key.equals("attributes=") || key.equals("composing=")) {
            int depth = 0;
            for (end = at + key.length(); end < descriptor.length(); end++) {
                char c = descriptor.charAt(end);
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    if (depth-- == 0) {
                        break;
                    }
                    if (depth == 0) {
                        end++;
                        break;
                    }
                }
            }
        }
        return descriptor.substring(at, Math.min(descriptor.length(), end < 0 ? descriptor.length() : end));
    }

    private static Map<String, String> keyed(List<String> lines) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : lines) {
            String t = line.trim();
            String key;
            if (t.startsWith("property ")) {
                key = "property " + t.substring("property ".length(), t.indexOf(' ', "property ".length()));
            } else if (t.startsWith("bean ")) {
                key = "bean";
            } else {
                key = t.substring(0, t.indexOf('='));
            }
            map.put(key, t);
        }
        return map;
    }

    private static String differingSegments(String a, String b) {
        String[] keys = {" type=", " cascaded=", " conversions=", " constraints=", " containers=", " constrained=", " constructors=", " methods="};
        Map<String, String> sa = segments(a, keys);
        Map<String, String> sb = segments(b, keys);
        List<String> differing = new ArrayList<>();
        for (String k : sa.keySet()) {
            if (!Objects.equals(sa.get(k), sb.get(k))) {
                differing.add(k.trim().replace("=", ""));
            }
        }
        return differing.isEmpty() ? "differs" : String.join("+", differing) + " differ";
    }

    private static Map<String, String> segments(String s, String[] keys) {
        Map<String, String> result = new TreeMap<>();
        List<int[]> positions = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            int at = s.indexOf(keys[i]);
            if (at >= 0) {
                positions.add(new int[] {at, i});
            }
        }
        positions.sort(Comparator.comparingInt(p -> p[0]));
        for (int i = 0; i < positions.size(); i++) {
            int start = positions.get(i)[0];
            int end = i + 1 < positions.size() ? positions.get(i + 1)[0] : s.length();
            result.put(keys[positions.get(i)[1]], s.substring(start, end));
        }
        return result;
    }

    private void compareViolations(Context ctx, CaseSpec spec, int instance, String check,
                                   Supplier<Set<? extends ConstraintViolation<?>>> mnCall,
                                   Supplier<Set<? extends ConstraintViolation<?>>> hvCall) {
        report.checks++;
        Outcome<List<Violation>> m = Outcome.of(() -> mnCall.get().stream().map(Canonical::violation).toList());
        Outcome<List<Violation>> h = Outcome.of(() -> hvCall.get().stream().map(Canonical::violation).toList());
        String checkKind = check.startsWith("validateProperty") ? "validateProperty" : check.startsWith("validateValue") ? "validateValue" : "validate";
        if (exceptionDiff(ctx, spec, instance, check, checkKind, m, h)) {
            return;
        }
        if (m.value == null || h.value == null) {
            return;
        }
        Map<String, List<Violation>> mnById = m.value.stream().collect(Collectors.groupingBy(Violation::identity, TreeMap::new, Collectors.toList()));
        Map<String, List<Violation>> hvById = h.value.stream().collect(Collectors.groupingBy(Violation::identity, TreeMap::new, Collectors.toList()));
        Set<String> ids = new LinkedHashSet<>(hvById.keySet());
        ids.addAll(mnById.keySet());
        String both = "Micronaut:\n" + list(m.value) + "Hibernate:\n" + list(h.value);
        String instanceSource = spec.instances.get(instance);
        for (String id : ids) {
            List<Violation> a = new ArrayList<>(mnById.getOrDefault(id, List.of()));
            List<Violation> b = new ArrayList<>(hvById.getOrDefault(id, List.of()));
            Comparator<Violation> order = Comparator.comparing(v -> v.details().toString());
            a.sort(order);
            b.sort(order);
            int common = Math.min(a.size(), b.size());
            for (int i = 0; i < common; i++) {
                Violation va = a.get(i);
                Violation vb = b.get(i);
                List<String> differing = new ArrayList<>();
                for (String key : vb.details().keySet()) {
                    if (!Objects.equals(va.details().get(key), vb.details().get(key))) {
                        differing.add(key);
                    }
                }
                if (differing.contains("attributeOrder")) {
                    differing.remove("attributeOrder");
                    if (!differing.contains("descriptor")) {
                        report.add(new Report.Difference("DETAIL", "DETAIL attribute class arrays (groups/payload) not in declaration order",
                            ctx.batch, spec.id, instance, check, "Micronaut: " + va.details().get("attributeOrder") + "\nHibernate: "
                            + vb.details().get("attributeOrder"), sourcesOf(spec, ctx.pkg), instanceSource));
                    }
                }
                if (differing.size() == 1 && differing.get(0).equals("pathString")) {
                    report.add(new Report.Difference("PATH_STRING", "PATH_STRING Micronaut " + pathShape(va.details().get("pathString"))
                        + " vs Hibernate " + pathShape(vb.details().get("pathString")),
                        ctx.batch, spec.id, instance, check, "Micronaut: " + va.details().get("pathString") + "\nHibernate: "
                        + vb.details().get("pathString") + "\n\n" + both, sourcesOf(spec, ctx.pkg), instanceSource));
                    differing.clear();
                }
                if (!differing.isEmpty()) {
                    String el = differing.contains("message") && vb.details().get("template").contains("${") ? " (EL template)" : "";
                    String detail = differing.stream().map(k -> k + ":\n    Micronaut: " + va.details().get(k) + "\n    Hibernate: " + vb.details().get(k))
                        .collect(Collectors.joining("\n"));
                    report.add(new Report.Difference("DETAIL", "DETAIL " + String.join("+", differing) + el + " " + location(ctx, vb) + flags(spec),
                        ctx.batch, spec.id, instance, check, id + "\n" + detail + "\n\n" + both, sourcesOf(spec, ctx.pkg), instanceSource));
                }
            }
            for (int i = common; i < b.size(); i++) {
                report.add(new Report.Difference("MISSING_IN_MICRONAUT", "MISSING_IN_MICRONAUT " + checkKind + " " + location(ctx, b.get(i)) + flags(spec),
                    ctx.batch, spec.id, instance, check, b.get(i).describe() + "\n\n" + both, sourcesOf(spec, ctx.pkg), instanceSource));
            }
            for (int i = common; i < a.size(); i++) {
                report.add(new Report.Difference("EXTRA_IN_MICRONAUT", "EXTRA_IN_MICRONAUT " + checkKind + " " + location(ctx, a.get(i)) + flags(spec),
                    ctx.batch, spec.id, instance, check, a.get(i).describe() + "\n\n" + both, sourcesOf(spec, ctx.pkg), instanceSource));
            }
        }
    }

    private boolean exceptionDiff(Context ctx, CaseSpec spec, int instance, String check, String checkKind, Outcome<?> m, Outcome<?> h) {
        if (m.error == null && h.error == null) {
            return false;
        }
        if (m.error != null && h.error != null) {
            String a = m.error.getClass().getName();
            String b = h.error.getClass().getName();
            if (!a.equals(b)) {
                report.add(new Report.Difference("EXCEPTION_TYPE", "EXCEPTION_TYPE " + checkKind + " Micronaut " + simple(a) + " vs Hibernate " + simple(b)
                    + " : " + normalize(Canonical.exceptionMessage(h.error)) + flags(spec),
                    ctx.batch, spec.id, instance, check, "Micronaut: " + Canonical.exception(m.error) + ": " + Canonical.exceptionMessage(m.error)
                    + "\nHibernate: " + Canonical.exception(h.error) + ": " + Canonical.exceptionMessage(h.error) + "\n" + trace(m.error),
                    sourcesOf(spec, ctx.pkg), instance < 0 ? null : spec.instances.get(instance)));
            }
            return true;
        }
        Throwable t = m.error != null ? m.error : h.error;
        String who = m.error != null ? "ONLY_MICRONAUT_THROWS" : "ONLY_HIBERNATE_THROWS";
        String other = m.error != null ? "Hibernate result: " + h.value : "Micronaut result: " + m.value;
        report.add(new Report.Difference(who, who + " " + checkKind + " " + simple(t.getClass().getName()) + " : "
            + normalize(Canonical.exceptionMessage(t)) + flags(spec),
            ctx.batch, spec.id, instance, check, Canonical.exception(t) + ": " + Canonical.exceptionMessage(t) + "\n" + other + "\n" + trace(t),
            sourcesOf(spec, ctx.pkg), instance < 0 ? null : spec.instances.get(instance)));
        return true;
    }

    private static String pathShape(String path) {
        String shape = path.replaceAll("\\bp\\d+\\b", "p").replaceAll("\\[[^\\]]*]", "[x]").replaceAll("\\bitems\\b", "p");
        String previous;
        do {
            previous = shape;
            shape = shape.replace("p.p", "p").replace("p[x].p", "p");
        } while (!previous.equals(shape));
        return shape;
    }

    private static String trace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        int depth = 0;
        while (c != null && depth++ < 3) {
            sb.append(c).append('\n');
            StackTraceElement[] st = c.getStackTrace();
            for (int i = 0; i < Math.min(8, st.length); i++) {
                sb.append("    at ").append(st[i]).append('\n');
            }
            c = c.getCause();
        }
        return sb.toString();
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    static String normalize(String message) {
        return message
            .replaceAll("fuzz\\.b\\d+\\.", "")
            .replaceAll("C\\d+_B\\d+", "C_B")
            .replaceAll("\\bp\\d+\\b", "p")
            .replaceAll("HV\\d+", "HV")
            .replaceAll("\\d+", "N")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static String list(List<Violation> violations) {
        if (violations.isEmpty()) {
            return "  (none)\n";
        }
        return violations.stream().map(v -> "  " + v.identity() + "  template=" + v.details().get("template")
            + "  message=" + v.details().get("message") + "  value=" + v.details().get("invalidValue") + "\n").sorted().collect(Collectors.joining());
    }

    private String location(Context ctx, Violation v) {
        String shape = v.identity().replaceAll("\\(([^)]*)\\)", "").replaceAll("\\[i=\\d+]", "[i]").replaceAll("\\[k=[^]]*]", "[k]");
        BeanSpec bean = v.leafBeanType() == null ? null : ctx.beans.get(v.leafBeanType());
        if (bean == null) {
            return shape + " leaf=" + v.leafBeanType();
        }
        if (v.lastProperty() == null || v.identity().startsWith("BEAN") && !v.identity().contains("PROPERTY")) {
            return shape + " class-level on " + flavor(bean) + classAnnoHint(bean, v.constraint());
        }
        PropertySpec p = findProperty(bean, v.lastProperty());
        if (p == null) {
            return shape + " " + flavor(bean) + classAnnoHint(bean, v.constraint());
        }
        String where = p.annos.stream().anyMatch(a -> a.name().equals(v.constraint())) ? "declared"
            : bean.overrides.stream().anyMatch(o -> o.name.equals(p.name)) ? "override"
            : "type-argument";
        return shape + " " + where + " on " + flavor(bean) + " " + p.type.describe() + (p.valid ? " @Valid" : "")
            + (p.convertGroup != null ? " @ConvertGroup" : "");
    }

    private static String classAnnoHint(BeanSpec bean, String constraint) {
        return bean.classAnnos.stream().filter(a -> a.name().equals(constraint)).findFirst()
            .map(a -> " " + a.render().replaceAll("groups = [^,)]*(, )?", "").replace("()", "")).orElse("");
    }

    private static String flavor(BeanSpec bean) {
        String kind = bean.record ? "record" : bean.getterConstraints ? "getter-bean" : "field-bean";
        if (bean.parent != null) {
            kind += "(subclass)";
        }
        if (bean.redefinesDefaultGroup) {
            kind += "(redefined-default)";
        }
        return kind;
    }

    private static PropertySpec findProperty(BeanSpec bean, String name) {
        for (PropertySpec p : bean.allProperties()) {
            if (p.name.equals(name)) {
                return p;
            }
        }
        return null;
    }

    private static String flags(CaseSpec spec) {
        boolean definitionError = false;
        boolean unsupported = false;
        for (BeanSpec bean : spec.beans) {
            List<Anno> all = new ArrayList<>(bean.classAnnos);
            for (PropertySpec p : bean.properties) {
                all.addAll(p.annos);
                collect(p.type, all);
            }
            for (PropertySpec p : bean.overrides) {
                all.addAll(p.annos);
            }
            for (Anno a : all) {
                definitionError |= a.definitionError();
                unsupported |= a.unsupportedType();
            }
        }
        return (definitionError ? " [invalid-definition]" : "") + (unsupported ? " [unsupported-type]" : "");
    }

    private static void collect(TypeNode type, List<Anno> all) {
        all.addAll(type.annos);
        for (TypeNode arg : type.args) {
            collect(arg, all);
        }
    }

    private static String sourcesOf(CaseSpec spec, String pkg) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (BeanSpec bean : spec.beans) {
            if (seen.add(bean.name)) {
                sb.append(SourceWriter.bean(bean)).append('\n');
            }
        }
        return sb.toString();
    }

    private static void write(Path root, Map<String, String> sources, List<Path> files) throws IOException {
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path file = root.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
            files.add(file);
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * A value or the exception computing it.
     */
    private static final class Outcome<T> {
        final T value;
        final Throwable error;

        Outcome(T value, Throwable error) {
            this.value = value;
            this.error = error;
        }

        static <T> Outcome<T> of(Supplier<T> supplier) {
            try {
                return new Outcome<>(supplier.get(), null);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) {
                    throw (VirtualMachineError) t;
                }
                return new Outcome<>(null, t);
            }
        }
    }
}
