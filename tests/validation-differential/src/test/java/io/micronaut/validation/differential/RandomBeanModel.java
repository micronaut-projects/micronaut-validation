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

import io.micronaut.validation.differential.TypeCatalog.Family;
import io.micronaut.validation.differential.TypeCatalog.Scalar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * A randomly generated validation model: one root bean, optional nested beans, random property shapes (scalars,
 * containers with type-argument constraints, {@code Optional}, arrays, cascaded beans), random built-in constraints
 * with random attributes, groups and messages, and an optional constrained method.
 *
 * <p>The structure is a pure function of {@code seed}. Every removable element carries an id; {@code removed} lists
 * the ids that shrinking took out, so a model is reproduced exactly from {@code (seed, removed)}.</p>
 */
final class RandomBeanModel {
    static final int SETS = 3;
    static final int GROUP_CHOICES = 5;

    private static final String[] CONSTRAINT_PACKAGE = {"jakarta.validation.constraints."};

    final String name;
    final long seed;
    final Set<Integer> removed;
    private final List<Bean> beans = new ArrayList<>();
    private final Map<Integer, String> elementTypes = new HashMap<>();
    private int nextId;

    private RandomBeanModel(String name, long seed, Set<Integer> removed) {
        this.name = name;
        this.seed = seed;
        this.removed = removed;
        generate(new SplittableRandom(seed));
    }

    static RandomBeanModel of(String name, long seed) {
        return new RandomBeanModel(name, seed, new TreeSet<>());
    }

    static RandomBeanModel of(String name, long seed, Set<Integer> removed) {
        return new RandomBeanModel(name, seed, new TreeSet<>(removed));
    }

    // ---------------------------------------------------------------- structure

    private enum ShapeKind { SCALAR, LIST, SET, MAP, OPTIONAL, ARRAY, NESTED, NESTED_LIST }

    private record Constraint(int id, String text) { }

    private static final class Property {
        int id;
        String name;
        ShapeKind kind;
        Scalar scalar;          // element/value scalar
        int nested = -1;        // nested bean index
        boolean getter;
        boolean cascade;
        List<Constraint> declaration = new ArrayList<>();
        List<Constraint> keyConstraints = new ArrayList<>();
        List<Constraint> element = new ArrayList<>();
        String[] values = new String[SETS + 1];
    }

    private static final class Parameter {
        int id;
        Scalar scalar;
        int nested = -1;
        List<Constraint> constraints = new ArrayList<>();
        String[] values = new String[SETS];
    }

    private static final class Method {
        int id;
        Scalar returnType;
        List<Constraint> returnConstraints = new ArrayList<>();
        List<Parameter> parameters = new ArrayList<>();
        String[] returnValues = new String[SETS];
    }

    private static final class Bean {
        int index;
        List<Property> properties = new ArrayList<>();
        Method method;
    }

    private void generate(SplittableRandom random) {
        int beanCount = 1 + (random.nextInt(3) == 0 ? 1 + random.nextInt(2) : 0);
        for (int i = 0; i < beanCount; i++) {
            Bean bean = new Bean();
            bean.index = i;
            beans.add(bean);
        }
        // generate leaf beans first so that nested references only point "down"
        for (int i = beanCount - 1; i >= 0; i--) {
            Bean bean = beans.get(i);
            int properties = 1 + random.nextInt(i == 0 ? 5 : 3);
            for (int p = 0; p < properties; p++) {
                bean.properties.add(property(random, bean, p, beanCount));
            }
            if (i == 0 && random.nextInt(3) == 0) {
                bean.method = method(random, beanCount);
            }
        }
    }

    private Property property(SplittableRandom random, Bean owner, int index, int beanCount) {
        Property property = new Property();
        property.id = nextId++;
        property.name = "p" + index;
        property.getter = random.nextInt(4) == 0;
        boolean canNest = owner.index + 1 < beanCount;
        int roll = random.nextInt(100);
        if (canNest && roll < 25) {
            property.kind = random.nextBoolean() ? ShapeKind.NESTED : ShapeKind.NESTED_LIST;
            property.nested = owner.index + 1 + random.nextInt(beanCount - owner.index - 1);
            property.cascade = random.nextInt(6) != 0;
        } else if (roll < 60) {
            property.kind = ShapeKind.SCALAR;
        } else {
            ShapeKind[] containers = {ShapeKind.LIST, ShapeKind.SET, ShapeKind.MAP, ShapeKind.OPTIONAL, ShapeKind.ARRAY};
            property.kind = containers[random.nextInt(containers.length)];
        }
        if (property.nested < 0) {
            property.scalar = pickScalar(random, property.kind != ShapeKind.SCALAR && property.kind != ShapeKind.ARRAY);
        }
        elementTypes.put(property.id, describe(property));
        switch (property.kind) {
            case SCALAR -> property.declaration.addAll(constraints(random, property.scalar.families(), false, property.scalar.primitive()));
            case LIST, SET, ARRAY -> {
                property.declaration.addAll(constraints(random, Set.of(Family.SIZED), false, false));
                if (property.kind != ShapeKind.ARRAY) {
                    property.element.addAll(constraints(random, property.scalar.families(), true, false));
                }
            }
            case MAP -> {
                property.declaration.addAll(constraints(random, Set.of(Family.SIZED), false, false));
                property.keyConstraints.addAll(constraints(random, Set.of(Family.CHARS), true, false));
                property.element.addAll(constraints(random, property.scalar.families(), true, false));
            }
            case OPTIONAL -> {
                // a declaration constraint on Optional: Hibernate Validator does not unwrap it implicitly
                if (random.nextInt(8) == 0) {
                    property.declaration.addAll(constraints(random, property.scalar.families(), false, false));
                } else {
                    property.element.addAll(constraints(random, property.scalar.families(), true, false));
                }
            }
            case NESTED -> {
                if (random.nextInt(3) == 0) {
                    property.declaration.add(new Constraint(nextId++, simple(random, "NotNull")));
                }
            }
            case NESTED_LIST -> property.declaration.addAll(constraints(random, Set.of(Family.SIZED), false, false));
            default -> throw new AssertionError();
        }
        for (int set = 0; set <= SETS; set++) {
            property.values[set] = value(random, property);
        }
        return property;
    }

    private Method method(SplittableRandom random, int beanCount) {
        Method method = new Method();
        method.id = nextId++;
        method.returnType = pickScalar(random, false);
        method.returnConstraints.addAll(constraints(random, method.returnType.families(), false, method.returnType.primitive()));
        int parameters = 1 + random.nextInt(3);
        for (int i = 0; i < parameters; i++) {
            Parameter parameter = new Parameter();
            parameter.id = nextId++;
            if (beanCount > 1 && random.nextInt(4) == 0) {
                parameter.nested = 1 + random.nextInt(beanCount - 1);
                parameter.constraints.add(new Constraint(nextId++, "@jakarta.validation.Valid"));
            } else {
                parameter.scalar = pickScalar(random, false);
                parameter.constraints.addAll(constraints(random, parameter.scalar.families(), false, parameter.scalar.primitive()));
            }
            for (int set = 0; set < SETS; set++) {
                parameter.values[set] = parameter.nested >= 0
                    ? (random.nextInt(4) == 0 ? "null" : "bean" + parameter.nested + "(" + random.nextInt(SETS) + ")")
                    : pick(random, parameter.scalar.valuesWithNull());
            }
            method.parameters.add(parameter);
        }
        for (int set = 0; set < SETS; set++) {
            method.returnValues[set] = pick(random, method.returnType.valuesWithNull());
        }
        return method;
    }

    private static Scalar pickScalar(SplittableRandom random, boolean boxedOnly) {
        while (true) {
            Scalar scalar = TypeCatalog.SCALARS.get(random.nextInt(TypeCatalog.SCALARS.size()));
            if (!boxedOnly || !scalar.primitive()) {
                return scalar;
            }
        }
    }

    // ---------------------------------------------------------------- constraints

    private List<Constraint> constraints(SplittableRandom random, Set<Family> families, boolean typeUse, boolean primitive) {
        List<Supplier<String>> candidates = new ArrayList<>();
        candidates.add(() -> simple(random, "NotNull"));
        if (random.nextInt(4) == 0) {
            candidates.add(() -> simple(random, "Null"));
        }
        for (Family family : families) {
            switch (family) {
                case BOOLEAN -> {
                    candidates.add(() -> simple(random, "AssertTrue"));
                    candidates.add(() -> simple(random, "AssertFalse"));
                }
                case INTEGRAL, BIG -> {
                    candidates.add(() -> minMax(random, "Min"));
                    candidates.add(() -> minMax(random, "Max"));
                    candidates.add(() -> decimal(random, "DecimalMin"));
                    candidates.add(() -> decimal(random, "DecimalMax"));
                    candidates.add(() -> digits(random));
                    addSigns(random, candidates);
                }
                case FLOATING -> addSigns(random, candidates);
                case CHARS -> {
                    candidates.add(() -> size(random));
                    candidates.add(() -> simple(random, "NotEmpty"));
                    candidates.add(() -> simple(random, "NotBlank"));
                    candidates.add(() -> pattern(random));
                    candidates.add(() -> email(random));
                    candidates.add(() -> decimal(random, "DecimalMin"));
                    candidates.add(() -> decimal(random, "DecimalMax"));
                    candidates.add(() -> digits(random));
                }
                case SIZED -> {
                    candidates.add(() -> size(random));
                    candidates.add(() -> simple(random, "NotEmpty"));
                }
                case TEMPORAL -> {
                    for (String temporal : new String[]{"Past", "PastOrPresent", "Future", "FutureOrPresent"}) {
                        candidates.add(() -> simple(random, temporal));
                    }
                }
                default -> throw new AssertionError(family);
            }
        }
        int count = random.nextInt(10) < 2 ? 0 : 1 + random.nextInt(Math.min(3, candidates.size()));
        List<Constraint> result = new ArrayList<>();
        List<Integer> chosen = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = random.nextInt(candidates.size());
            if (chosen.contains(index) && random.nextInt(4) != 0) {
                continue; // mostly distinct constraints, occasionally a repeated one
            }
            chosen.add(index);
            result.add(new Constraint(nextId++, candidates.get(index).get()));
        }
        return result;
    }

    private static void addSigns(SplittableRandom random, List<Supplier<String>> candidates) {
        for (String sign : new String[]{"Positive", "PositiveOrZero", "Negative", "NegativeOrZero"}) {
            candidates.add(() -> simple(random, sign));
        }
    }

    private static String simple(SplittableRandom random, String type) {
        return annotation(random, type, "");
    }

    private static String minMax(SplittableRandom random, String type) {
        String[] values = {"0", "1", "-1", "5", "127", "128", "-9223372036854775808L", "9223372036854775807L"};
        return annotation(random, type, "value = " + pick(random, values));
    }

    private static String decimal(SplittableRandom random, String type) {
        String[] values = {"0", "-0.5", "1.5", "10", "1e2", "0.0001", "5", "-9223372036854775809", "127.5", "0.00"};
        String inclusive = random.nextInt(3) == 0 ? ", inclusive = " + random.nextBoolean() : "";
        return annotation(random, type, "value = \"" + pick(random, values) + "\"" + inclusive);
    }

    private static String digits(SplittableRandom random) {
        return annotation(random, "Digits", "integer = " + random.nextInt(5) + ", fraction = " + random.nextInt(4));
    }

    private static String size(SplittableRandom random) {
        int min = random.nextInt(4);
        String attributes = random.nextInt(3) == 0 ? "min = " + min : "min = " + min + ", max = " + (min + random.nextInt(4));
        if (random.nextInt(5) == 0) {
            attributes = "max = " + random.nextInt(4);
        }
        return annotation(random, "Size", attributes);
    }

    private static String pattern(SplittableRandom random) {
        String[] expressions = {"[a-z]*", "^a.*", "\\\\d+", ".*", "a|b", "abc", "\\\\p{L}+", "[\\\\s\\\\S]*b", "x*", "\\\\S+"};
        return annotation(random, "Pattern", "regexp = \"" + pick(random, expressions) + "\"" + flags(random));
    }

    private static String email(SplittableRandom random) {
        String attributes = random.nextInt(4) == 0 ? "regexp = \".*\\\\.com\"" + flags(random) : "";
        return annotation(random, "Email", attributes);
    }

    private static String flags(SplittableRandom random) {
        if (random.nextInt(3) != 0) {
            return "";
        }
        String[] flags = {"CASE_INSENSITIVE", "DOTALL", "MULTILINE", "UNICODE_CASE", "COMMENTS"};
        List<String> selected = new ArrayList<>();
        for (String flag : flags) {
            if (random.nextInt(3) == 0) {
                selected.add("jakarta.validation.constraints.Pattern.Flag." + flag);
            }
        }
        return ", flags = {" + String.join(", ", selected) + "}";
    }

    private static String annotation(SplittableRandom random, String type, String attributes) {
        List<String> parts = new ArrayList<>();
        if (!attributes.isEmpty()) {
            parts.add(attributes);
        }
        int groups = random.nextInt(10);
        if (groups == 7) {
            parts.add("groups = G1.class");
        } else if (groups == 8) {
            parts.add("groups = {G1.class, G2.class}");
        } else if (groups == 9) {
            parts.add("groups = {jakarta.validation.groups.Default.class, G2.class}");
        }
        int message = random.nextInt(20);
        if (message < 3) {
            parts.add("message = \"custom-" + type + "\"");
        } else if (message < 6) {
            parts.add("message = \"" + attributeTemplate(type) + "\"");
        } else if (message == 6) {
            parts.add("message = \"v=${validatedValue}\"");
        } else if (message == 7) {
            parts.add("message = \"{jakarta.validation.constraints." + type + ".message} [nested]\"");
        } else if (message == 8) {
            parts.add("message = \"literal \\\\{escaped\\\\} and \\\\$ dollar\"");
        }
        return "@" + CONSTRAINT_PACKAGE[0] + type + (parts.isEmpty() ? "" : "(" + String.join(", ", parts) + ")");
    }

    private static String attributeTemplate(String type) {
        return switch (type) {
            case "Size" -> "between {min} and {max}";
            case "Min", "Max" -> "limit {value}";
            case "DecimalMin", "DecimalMax" -> "limit {value} inclusive {inclusive}";
            case "Digits" -> "{integer}.{fraction}";
            case "Pattern" -> "regexp {regexp} flags {flags}";
            case "Email" -> "regexp {regexp}";
            default -> "groups {groups} payload {payload}";
        };
    }

    // ---------------------------------------------------------------- values

    private String value(SplittableRandom random, Property property) {
        if (property.kind != ShapeKind.SCALAR && random.nextInt(8) == 0) {
            return "null";
        }
        return switch (property.kind) {
            case SCALAR -> pick(random, property.scalar.valuesWithNull());
            case LIST, SET -> {
                List<String> elements = elements(random, property.scalar);
                String list = "list(" + String.join(", ", elements) + ")";
                yield property.kind == ShapeKind.SET ? "new java.util.LinkedHashSet<>(" + list + ")" : list;
            }
            case ARRAY -> {
                List<String> elements = new ArrayList<>();
                int size = random.nextInt(4);
                for (int i = 0; i < size; i++) {
                    elements.add(pick(random, property.scalar.primitive() ? property.scalar.values() : property.scalar.valuesWithNull()));
                }
                yield "new " + property.scalar.name() + "[]{" + String.join(", ", elements) + "}";
            }
            case MAP -> {
                List<String> entries = new ArrayList<>();
                int size = random.nextInt(4);
                Scalar strings = TypeCatalog.SCALARS.stream().filter(s -> s.name().equals("String")).findFirst().orElseThrow();
                for (int i = 0; i < size; i++) {
                    entries.add(typed(pick(random, strings.valuesWithNull()), "String"));
                    entries.add(typed(pick(random, property.scalar.valuesWithNull()), property.scalar.boxed()));
                }
                yield "map(" + String.join(", ", entries) + ")";
            }
            case OPTIONAL -> {
                String element = pick(random, property.scalar.valuesWithNull());
                yield element.equals("null") ? "java.util.Optional.empty()" : "java.util.Optional.of(" + element + ")";
            }
            case NESTED -> random.nextInt(5) == 0 ? "null" : "bean" + property.nested + "(" + random.nextInt(SETS) + ")";
            case NESTED_LIST -> {
                List<String> elements = new ArrayList<>();
                int size = random.nextInt(3);
                for (int i = 0; i < size; i++) {
                    elements.add(random.nextInt(5) == 0 ? "(Bean" + property.nested + ") null" : "bean" + property.nested + "(" + random.nextInt(SETS) + ")");
                }
                yield "list(" + String.join(", ", elements) + ")";
            }
        };
    }

    private static List<String> elements(SplittableRandom random, Scalar scalar) {
        List<String> elements = new ArrayList<>();
        int size = random.nextInt(4);
        for (int i = 0; i < size; i++) {
            elements.add(typed(pick(random, scalar.valuesWithNull()), scalar.boxed()));
        }
        return elements;
    }

    private static String typed(String value, String type) {
        return "(" + type + ") (" + value + ")";
    }

    private static <T> T pick(SplittableRandom random, List<T> values) {
        return values.get(random.nextInt(values.size()));
    }

    private static String pick(SplittableRandom random, String[] values) {
        return values[random.nextInt(values.length)];
    }

    // ---------------------------------------------------------------- rendering

    boolean isRemoved(int id) {
        return removed.contains(id);
    }

    /** All ids that are still present and may be removed by shrinking, largest structures first. */
    List<Integer> removableIds() {
        List<Integer> ids = new ArrayList<>();
        for (Bean bean : beans) {
            if (bean.method != null && !isRemoved(bean.method.id)) {
                ids.add(bean.method.id);
            }
            for (Property property : bean.properties) {
                if (!isRemoved(property.id)) {
                    ids.add(property.id);
                }
            }
        }
        for (Bean bean : beans) {
            if (bean.method != null && !isRemoved(bean.method.id)) {
                for (Parameter parameter : bean.method.parameters) {
                    if (!isRemoved(parameter.id) && bean.method.parameters.stream().filter(p -> !isRemoved(p.id)).count() > 1) {
                        ids.add(parameter.id);
                    }
                    parameter.constraints.stream().map(Constraint::id).filter(id -> !isRemoved(id)).forEach(ids::add);
                }
                bean.method.returnConstraints.stream().map(Constraint::id).filter(id -> !isRemoved(id)).forEach(ids::add);
            }
            for (Property property : bean.properties) {
                for (List<Constraint> constraints : List.of(property.declaration, property.keyConstraints, property.element)) {
                    constraints.stream().map(Constraint::id).filter(id -> !isRemoved(id)).forEach(ids::add);
                }
            }
        }
        return ids;
    }

    RandomBeanModel renamed(String newName) {
        return new RandomBeanModel(newName, seed, removed);
    }

    RandomBeanModel without(int id) {
        return without(Set.of(id));
    }

    RandomBeanModel without(Set<Integer> ids) {
        Set<Integer> next = new TreeSet<>(removed);
        next.addAll(ids);
        return new RandomBeanModel(name, seed, next);
    }

    boolean hasMethod() {
        return beans.get(0).method != null && !isRemoved(beans.get(0).method.id);
    }

    /** Declared shape of the property or parameter for a given path leaf name, for reports. */
    String describeLeaf(String leaf) {
        for (Bean bean : beans) {
            for (Property property : bean.properties) {
                if (property.name.equals(leaf)) {
                    return elementTypes.get(property.id);
                }
            }
        }
        return "?";
    }

    private static String describe(Property property) {
        return switch (property.kind) {
            case SCALAR -> property.scalar.name();
            case LIST -> "List<" + property.scalar.boxed() + ">";
            case SET -> "Set<" + property.scalar.boxed() + ">";
            case MAP -> "Map<String," + property.scalar.boxed() + ">";
            case OPTIONAL -> "Optional<" + property.scalar.boxed() + ">";
            case ARRAY -> property.scalar.name() + "[]";
            case NESTED -> "Bean";
            case NESTED_LIST -> "List<Bean>";
        };
    }

    private String constraints(List<Constraint> constraints) {
        StringBuilder text = new StringBuilder();
        for (Constraint constraint : constraints) {
            if (!isRemoved(constraint.id)) {
                text.append(constraint.text).append(' ');
            }
        }
        return text.toString();
    }

    private String declaredType(Property property) {
        String element = constraints(property.element);
        return switch (property.kind) {
            case SCALAR -> property.scalar.name();
            case LIST -> "java.util.List<" + element + property.scalar.boxed() + ">";
            case SET -> "java.util.Set<" + element + property.scalar.boxed() + ">";
            case MAP -> "java.util.Map<" + constraints(property.keyConstraints) + "String, " + element + property.scalar.boxed() + ">";
            case OPTIONAL -> "java.util.Optional<" + element + property.scalar.boxed() + ">";
            case ARRAY -> property.scalar.name() + "[]";
            case NESTED -> "Bean" + property.nested;
            case NESTED_LIST -> "java.util.List<" + (property.cascade ? "@jakarta.validation.Valid " : "") + "Bean" + property.nested + ">";
        };
    }

    private String nameType(Property property) {
        // used for validateValue: the raw declared type without type-use annotations
        return switch (property.kind) {
            case SCALAR -> property.scalar.name();
            case ARRAY -> property.scalar.name() + "[]";
            case LIST -> "java.util.List<" + property.scalar.boxed() + ">";
            case SET -> "java.util.Set<" + property.scalar.boxed() + ">";
            case MAP -> "java.util.Map<String, " + property.scalar.boxed() + ">";
            case OPTIONAL -> "java.util.Optional<" + property.scalar.boxed() + ">";
            case NESTED -> "Bean" + property.nested;
            case NESTED_LIST -> "java.util.List<Bean" + property.nested + ">";
        };
    }

    private List<Property> live(Bean bean) {
        return bean.properties.stream().filter(p -> !isRemoved(p.id)).toList();
    }

    String source() {
        StringBuilder s = new StringBuilder();
        s.append("package fuzz;\n\n");
        s.append("import io.micronaut.core.annotation.Introspected;\n");
        s.append("import jakarta.validation.*;\n");
        s.append("import java.util.*;\n");
        s.append("import java.math.*;\nimport java.time.*;\nimport java.time.chrono.*;\n\n");
        s.append("/** seed=").append(seed).append(" removed=").append(removed).append(" */\n");
        s.append("public class ").append(name).append(" {\n");
        s.append("    public interface G1 {}\n    public interface G2 {}\n");
        s.append("    @GroupSequence({G1.class, G2.class}) public interface Seq {}\n\n");
        for (Bean bean : beans) {
            s.append("    @Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD})\n");
            s.append("    public static class Bean").append(bean.index).append(" {\n");
            for (Property property : live(bean)) {
                String annotations = constraints(property.declaration)
                    + (property.kind == ShapeKind.NESTED && property.cascade ? "@jakarta.validation.Valid " : "");
                String type = declaredType(property);
                if (property.getter) {
                    s.append("        private ").append(type).append(' ').append(property.name).append(";\n");
                    s.append("        ").append(annotations).append("public ").append(type).append(" get")
                        .append(Character.toUpperCase(property.name.charAt(0))).append(property.name.substring(1))
                        .append("() { return ").append(property.name).append("; }\n");
                } else {
                    s.append("        ").append(annotations).append("public ").append(type).append(' ').append(property.name).append(";\n");
                }
            }
            Method method = bean.method;
            if (method != null && !isRemoved(method.id)) {
                s.append("        ").append(constraints(method.returnConstraints)).append("public ").append(method.returnType.name()).append(" m(");
                List<String> parameters = new ArrayList<>();
                int index = 0;
                for (Parameter parameter : method.parameters) {
                    if (!isRemoved(parameter.id)) {
                        parameters.add(constraints(parameter.constraints) + parameterType(parameter) + " a" + index);
                    }
                    index++;
                }
                s.append(String.join(", ", parameters)).append(") { throw new UnsupportedOperationException(); }\n");
            }
            s.append("    }\n\n");
            s.append("    static Bean").append(bean.index).append(" bean").append(bean.index).append("(int set) {\n");
            s.append("        Bean").append(bean.index).append(" bean = new Bean").append(bean.index).append("();\n");
            s.append("        switch (set) {\n");
            for (int set = 0; set < SETS; set++) {
                s.append("            case ").append(set).append(" -> {");
                for (Property property : live(bean)) {
                    s.append(" bean.").append(property.name).append(" = ").append(valueFor(property, set)).append(';');
                }
                s.append(" }\n");
            }
            s.append("            default -> throw new IllegalArgumentException();\n        }\n        return bean;\n    }\n\n");
        }
        renderRun(s);
        s.append("    @SafeVarargs static <T> List<T> list(T... values) { return new ArrayList<>(Arrays.asList(values)); }\n");
        s.append("    @SuppressWarnings(\"unchecked\") static <K, V> Map<K, V> map(Object... entries) {\n");
        s.append("        Map<K, V> map = new LinkedHashMap<>();\n");
        s.append("        for (int i = 0; i < entries.length; i += 2) { map.put((K) entries[i], (V) entries[i + 1]); }\n");
        s.append("        return map;\n    }\n");
        s.append("    static Calendar calendar(long delta) {\n");
        s.append("        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(\"UTC\"), Locale.ENGLISH);\n");
        s.append("        calendar.setTimeInMillis(").append(TypeCatalog.NOW_MILLIS).append(" + delta);\n        return calendar;\n    }\n");
        s.append("}\n");
        return s.toString();
    }

    private String valueFor(Property property, int set) {
        String value = property.values[set];
        if (property.nested >= 0 && value.contains("bean")) {
            // a nested bean that is generated after its user references only live beans; always valid
            return value;
        }
        if (property.kind == ShapeKind.SCALAR && property.scalar.primitive() && value.equals("null")) {
            return property.scalar.values().get(0);
        }
        return value;
    }

    private String parameterType(Parameter parameter) {
        return parameter.nested >= 0 ? "Bean" + parameter.nested : parameter.scalar.name();
    }

    private void renderRun(StringBuilder s) {
        Bean root = beans.get(0);
        List<Property> properties = live(root);
        s.append("    public static Collection<? extends ConstraintViolation<?>> run(Validator validator, int operation, long input) throws Exception {\n");
        s.append("        int set = (int) Math.floorMod(input, ").append(SETS).append(");\n");
        s.append("        int groupChoice = (int) Math.floorMod(input / ").append(SETS).append(", ").append(GROUP_CHOICES).append(");\n");
        s.append("        int pick = (int) Math.floorMod(input / ").append(SETS * GROUP_CHOICES).append(", 64);\n");
        s.append("        Class<?>[] groups = switch (groupChoice) {\n");
        s.append("            case 0 -> new Class<?>[0];\n");
        s.append("            case 1 -> new Class<?>[]{G1.class};\n");
        s.append("            case 2 -> new Class<?>[]{G2.class};\n");
        s.append("            case 3 -> new Class<?>[]{jakarta.validation.groups.Default.class, G1.class};\n");
        s.append("            default -> new Class<?>[]{Seq.class};\n");
        s.append("        };\n");
        s.append("        Bean0 bean = bean0(set);\n");
        s.append("        switch (operation) {\n");
        if (!properties.isEmpty()) {
            s.append("            case 1: switch (pick % ").append(properties.size()).append(") {\n");
            for (int i = 0; i < properties.size(); i++) {
                s.append("                case ").append(i).append(": return validator.validateProperty(bean, \"").append(properties.get(i).name).append("\", groups);\n");
            }
            s.append("                default: throw new AssertionError();\n            }\n");
            s.append("            case 2: switch (pick % ").append(properties.size()).append(") {\n");
            for (int i = 0; i < properties.size(); i++) {
                Property property = properties.get(i);
                s.append("                case ").append(i).append(": { ").append(nameType(property)).append(" value = ")
                    .append(valueFor(property, SETS)).append("; return validator.validateValue(Bean0.class, \"")
                    .append(property.name).append("\", value, groups); }\n");
            }
            s.append("                default: throw new AssertionError();\n            }\n");
        }
        Method method = root.method;
        if (method != null && !isRemoved(method.id)) {
            List<String> types = new ArrayList<>();
            List<Parameter> parameters = method.parameters.stream().filter(p -> !isRemoved(p.id)).toList();
            for (Parameter parameter : parameters) {
                types.add(parameterType(parameter) + ".class");
            }
            s.append("            case 3: {\n");
            s.append("                java.lang.reflect.Method method = Bean0.class.getMethod(\"m\"").append(types.isEmpty() ? "" : ", " + String.join(", ", types)).append(");\n");
            s.append("                Object[] arguments = switch (set) {\n");
            for (int set = 0; set < SETS; set++) {
                List<String> values = new ArrayList<>();
                for (Parameter parameter : parameters) {
                    String value = parameter.values[set];
                    if (parameter.scalar != null && parameter.scalar.primitive() && value.equals("null")) {
                        value = parameter.scalar.values().get(0);
                    }
                    values.add(value);
                }
                s.append("                    case ").append(set).append(" -> new Object[]{").append(String.join(", ", values)).append("};\n");
            }
            s.append("                    default -> throw new AssertionError();\n                };\n");
            s.append("                return validator.forExecutables().validateParameters(bean, method, arguments, groups);\n            }\n");
            s.append("            case 4: {\n");
            s.append("                java.lang.reflect.Method method = Bean0.class.getMethod(\"m\"").append(types.isEmpty() ? "" : ", " + String.join(", ", types)).append(");\n");
            s.append("                Object value = switch (set) {\n");
            for (int set = 0; set < SETS; set++) {
                String value = method.returnValues[set];
                if (method.returnType.primitive() && value.equals("null")) {
                    value = method.returnType.values().get(0);
                }
                s.append("                    case ").append(set).append(" -> ").append(value.equals("null") ? "(Object) null" : value).append(";\n");
            }
            s.append("                    default -> throw new AssertionError();\n                };\n");
            s.append("                return validator.forExecutables().validateReturnValue(bean, method, value, groups);\n            }\n");
        }
        s.append("            default: return validator.validate(bean, groups);\n");
        s.append("        }\n    }\n\n");
    }

    /** Reproduction key for the regression corpus. */
    String corpusEntry() {
        StringBuilder text = new StringBuilder("RANDOM," + seed);
        for (int id : removed) {
            text.append(',').append(id);
        }
        return text.toString();
    }

    static RandomBeanModel fromCorpus(String name, String line) {
        String[] parts = line.split(",");
        Set<Integer> removed = new TreeSet<>();
        for (int i = 2; i < parts.length; i++) {
            removed.add(Integer.parseInt(parts[i].trim()));
        }
        return of(name, Long.parseLong(parts[1].trim()), removed);
    }
}
