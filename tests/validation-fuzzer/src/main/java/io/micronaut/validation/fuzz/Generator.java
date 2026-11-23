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
import io.micronaut.validation.fuzz.Model.MethodSpec;
import io.micronaut.validation.fuzz.Model.PropertySpec;
import io.micronaut.validation.fuzz.Model.ServiceSpec;
import io.micronaut.validation.fuzz.Model.TypeNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Generates random constrained bean graphs and the Java expressions building instances of them.
 */
final class Generator {

    static final List<String> GROUPS = List.of("G1", "G2", "G3");

    private static final Kind[] SCALARS = {
        Kind.STRING, Kind.STRING, Kind.STRING, Kind.CHAR_SEQUENCE, Kind.INT, Kind.INTEGER, Kind.INTEGER, Kind.LONG,
        Kind.LONG_W, Kind.SHORT_W, Kind.BYTE_W, Kind.DOUBLE, Kind.DOUBLE_W, Kind.FLOAT_W, Kind.BIG_DECIMAL,
        Kind.BIG_INTEGER, Kind.BOOLEAN, Kind.BOOLEAN_W, Kind.LOCAL_DATE, Kind.LOCAL_DATE_TIME, Kind.INSTANT,
        Kind.ZONED_DATE_TIME, Kind.OFFSET_DATE_TIME, Kind.DATE, Kind.YEAR, Kind.YEAR_MONTH, Kind.ENUM
    };

    private static final String[][] PATTERNS = {
        // regexp, matching, not matching
        {"\"[a-z]+\"", "abc", "ABC"},
        {"\"\\\\d{2,4}\"", "123", "1"},
        {"\"a|b\"", "a", "ab"},
        {"\"[a-z]\"", "q", "qq"},
        {"\".*\"", "anything", null},
        {"\"^$\"", "", " "},
        {"\"\\\\p{Alpha}+\"", "Hello", "h3llo"},
        {"\"\\\\w+@\\\\w+\"", "a@b", "a@"},
        {"\"[^ ]*\"", "nospace", "a space"},
        {"\"(?i)abc\"", "AbC", "abd"},
        {"\"ü+\"", "üü", "u"},
    };

    private static final String[] STRINGS = {
        "", " ", "  ", "a", "ab", "abc", "abcd", "abcde", "abcdefghijk", "ABC", "123", "-12.50", "0", "10", "1e3",
        "x y", "\\t", "\\n", "ü", "日本", "foo@bar.com", "not-an-email", "a@b", "@b.com", "a@", "a b@c.com",
        "a@b..com", "x@[127.0.0.1]", "üser@exämple.com", "a.b@c.d.e", ".a@b.com", "a@-b.com",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa@b.com", "99999999999999999999",
        "-0", "+5", "0.000", "12345.678", "NaN", " 1", "1 "
    };

    private static final String[] MESSAGES = {
        "\"custom literal\"",
        "\"{jakarta.validation.constraints.NotNull.message}\"",
        "\"bad value ${validatedValue}\"",
        "\"escaped \\\\{min\\\\} \\\\$ \\\\\\\\\"",
        "\"{fuzz.missing.key}\"",
        "\"${1 + 1} items\"",
        "\"${formatter.format('%s!', validatedValue)}\"",
        "\"unclosed {min\"",
        "\"$ dollar {regexp} {value} {inclusive}\"",
        "\"{min}..{max} groups={groups}\"",
        "\"${validatedValue == null ? 'null' : 'set'}\"",
        "\"{fuzz.bundle.key}\"",
        "\"{fuzz.bundle.nested}\"",
        "\"{}\"",
        "\"$\"",
        "\"${\"",
    };

    private final Random random;
    private int beanCounter;
    private int propertyCounter;

    Generator(Random random) {
        this.random = random;
    }

    CaseSpec generateCase(int id) {
        CaseSpec spec = new CaseSpec(id);
        beanCounter = 0;
        spec.root = generateBean(spec, 0, null);
        int instanceCount = 3 + random.nextInt(3);
        for (int i = 0; i < instanceCount; i++) {
            spec.instances.add(instance(spec.root, 0));
        }
        generateServices(spec);
        return spec;
    }

    // ---------------------------------------------------------------- services (executable validation)

    private int methodCounter;

    private void generateServices(CaseSpec spec) {
        if (!chance(0.6)) {
            return;
        }
        methodCounter = 0;
        ServiceSpec implemented = null;
        if (chance(0.25)) {
            implemented = new ServiceSpec("C" + spec.id + "_I", true, null, null);
            int count = 1 + random.nextInt(2);
            for (int i = 0; i < count; i++) {
                implemented.methods.add(method(spec));
            }
            spec.services.add(implemented);
        }
        ServiceSpec base = new ServiceSpec("C" + spec.id + "_S0", false, null, implemented);
        base.constructor = constructor(spec);
        int count = 1 + random.nextInt(3);
        for (int i = 0; i < count; i++) {
            MethodSpec m = method(spec);
            base.methods.add(m);
            if (chance(0.1)) {
                // an overload with another arity
                MethodSpec overload = method(spec, m.name);
                if (overload.params.size() != m.params.size()) {
                    base.methods.add(overload);
                }
            }
        }
        if (implemented != null) {
            for (MethodSpec m : implemented.methods) {
                base.methods.add(override(m));
            }
        }
        spec.services.add(base);
        if (chance(0.35)) {
            ServiceSpec sub = new ServiceSpec("C" + spec.id + "_S1", false, base, null);
            sub.constructor = constructor(spec);
            List<MethodSpec> candidates = base.methods;
            int overrides = 1 + random.nextInt(Math.min(2, candidates.size()));
            for (int i = 0; i < overrides; i++) {
                MethodSpec target = candidates.get(random.nextInt(candidates.size()));
                if (sub.methods.stream().noneMatch(o -> o.overridden == target)) {
                    sub.methods.add(override(target));
                }
            }
            if (chance(0.3)) {
                sub.methods.add(method(spec));
            }
            spec.services.add(sub);
        }
        for (ServiceSpec service : spec.services) {
            if (service.isInterface) {
                continue;
            }
            String receiver = receiver(spec, service);
            MethodSpec ctor = service.constructor;
            for (int i = 0; i < 2; i++) {
                spec.calls.add(call(service.name, ctor, "null", spec));
            }
            for (MethodSpec m : service.callable()) {
                int n = 2 + random.nextInt(2);
                for (int i = 0; i < n; i++) {
                    spec.calls.add(call(service.declaring(m).name, m, receiver, spec));
                }
            }
        }
    }

    private String receiver(CaseSpec spec, ServiceSpec service) {
        return "new " + service.name + "(" + service.constructor.params.stream().map(p -> value(p.type, 1, p))
            .collect(Collectors.joining(", ")) + ")";
    }

    private String call(String declaring, MethodSpec m, String receiver, CaseSpec spec) {
        String args = m.params.stream().map(p -> value(p.type, 1, p)).collect(Collectors.joining(", "));
        String ret = m.ret == null ? "null" : value(m.ret.type, 1, m.ret);
        return "new Object[] {\"" + declaring + "\", \"" + m.name + "\", " + m.params.size() + ", " + receiver
            + ", new Object[] {" + args + "}, " + ret + "}";
    }

    private MethodSpec constructor(CaseSpec spec) {
        MethodSpec ctor = new MethodSpec("<init>");
        int n = chance(0.4) ? 0 : 1 + random.nextInt(3);
        for (int i = 0; i < n; i++) {
            ctor.params.add(parameter(spec, "c" + i));
        }
        maybeCross(ctor);
        return ctor;
    }

    private MethodSpec method(CaseSpec spec) {
        return method(spec, "m" + (methodCounter++));
    }

    private MethodSpec method(CaseSpec spec, String name) {
        MethodSpec m = new MethodSpec(name);
        int n = random.nextInt(4);
        for (int i = 0; i < n; i++) {
            m.params.add(parameter(spec, "a" + i));
        }
        if (chance(0.75)) {
            TypeNode type = parameterType(spec);
            PropertySpec ret = new PropertySpec("<return>", type);
            ret.annos.addAll(declarationConstraints(type, false));
            cascade(ret);
            m.ret = ret;
        }
        maybeCross(m);
        return m;
    }

    private void maybeCross(MethodSpec m) {
        if (!m.params.isEmpty() && chance(0.2)) {
            Anno cross = new Anno("FuzzParams").with("mode", String.valueOf(random.nextInt(4)));
            maybeMessage(cross);
            maybeGroups(cross);
            m.cross.add(cross);
        }
    }

    private PropertySpec parameter(CaseSpec spec, String name) {
        TypeNode type = parameterType(spec);
        PropertySpec p = new PropertySpec(name, type);
        p.annos.addAll(declarationConstraints(type, false));
        cascade(p);
        return p;
    }

    private void cascade(PropertySpec p) {
        TypeNode type = p.type;
        boolean cascadable = type.kind == Kind.BEAN || (type.kind == Kind.LIST && type.args.get(0).kind == Kind.BEAN);
        if (cascadable && chance(0.7)) {
            p.valid = true;
            if (chance(0.15)) {
                p.convertGroup = chance(0.5) ? "from = Default.class, to = G1.class" : "from = G2.class, to = G3.class";
            }
        }
    }

    /** A scalar or container type, or one of the beans of the case: no new bean types. */
    private TypeNode parameterType(CaseSpec spec) {
        if (!spec.beans.isEmpty() && chance(0.25)) {
            TypeNode bean = TypeNode.bean(spec.beans.get(random.nextInt(spec.beans.size())));
            if (chance(0.3)) {
                TypeNode list = TypeNode.of(Kind.LIST, bean);
                bean.annos.addAll(typeUseConstraints(bean));
                maybeCascadeArg(bean);
                return list;
            }
            return bean;
        }
        return generateType(spec, 2, 0);
    }

    /**
     * A method overriding or implementing another one, with the same signature. The modes adding parameter
     * constraints or a second cascade break the rules of section 5.6.5 of the specification.
     */
    private MethodSpec override(MethodSpec target) {
        MethodSpec m = new MethodSpec(target.name);
        m.overridden = target;
        for (PropertySpec p : target.params) {
            m.params.add(new PropertySpec(p.name, p.type));
        }
        if (target.ret != null) {
            m.ret = new PropertySpec("<return>", target.ret.type);
        }
        List<String> modes = new ArrayList<>(List.of("inherit", "inherit", "inherit"));
        if (m.ret != null) {
            modes.addAll(List.of("return-constraint", "return-constraint"));
            if (target.ret.valid) {
                modes.add("return-cascade-again");
            }
        }
        if (!m.params.isEmpty()) {
            modes.addAll(List.of("parameter-constraint", "parameter-cascade"));
            if (target.cross.isEmpty()) {
                modes.add("cross-parameter");
            }
        }
        String mode = modes.get(random.nextInt(modes.size()));
        m.overrideMode = mode;
        switch (mode) {
            case "return-constraint" -> m.ret.annos.addAll(constraintsFor(m.ret.type, 1, false));
            case "return-cascade-again" -> {
                m.ret.valid = true;
                m.definitionError = true;
            }
            case "parameter-constraint" -> {
                PropertySpec p = m.params.get(random.nextInt(m.params.size()));
                p.annos.addAll(constraintsFor(p.type, 1, false));
                m.definitionError = !p.annos.isEmpty();
            }
            case "parameter-cascade" -> {
                PropertySpec p = m.params.get(random.nextInt(m.params.size()));
                p.valid = true;
                m.definitionError = true;
            }
            case "cross-parameter" -> {
                m.cross.add(new Anno("FuzzParams").with("mode", String.valueOf(random.nextInt(4))));
                m.definitionError = true;
            }
            default -> { }
        }
        return m;
    }

    // ---------------------------------------------------------------- beans

    private BeanSpec generateBean(CaseSpec spec, int depth, BeanSpec forcedParent) {
        boolean record = forcedParent == null && chance(0.2);
        BeanSpec parent = forcedParent;
        if (!record && parent == null && depth < 2 && chance(0.15)) {
            parent = generateBean(spec, depth + 1, null);
            if (parent.record) {
                parent = null;
            }
        }
        String name = "C" + spec.id + "_B" + (beanCounter++);
        BeanSpec bean = new BeanSpec(name, record, !record && chance(0.25), parent);
        int count = 1 + random.nextInt(4);
        for (int i = 0; i < count; i++) {
            bean.properties.add(generateProperty(spec, bean, depth));
        }
        if (parent != null && !parent.properties.isEmpty() && chance(0.4)) {
            PropertySpec overridden = parent.properties.get(random.nextInt(parent.properties.size()));
            PropertySpec addition = new PropertySpec(overridden.name, overridden.type);
            addition.annos.addAll(declarationConstraints(overridden.type, true));
            if (!addition.annos.isEmpty()) {
                bean.overrides.add(addition);
            }
        }
        if (chance(0.15)) {
            int mode = random.nextInt(7);
            Anno check = new Anno("FuzzClassCheck").with("mode", String.valueOf(mode));
            maybeGroups(check);
            bean.classAnnos.add(check);
        }
        if (chance(0.08)) {
            // redefine the default group sequence of the bean
            bean.redefinesDefaultGroup = true;
        }
        spec.beans.add(bean);
        return bean;
    }

    private PropertySpec generateProperty(CaseSpec spec, BeanSpec owner, int depth) {
        String name = "p" + (propertyCounter++ % 1000);
        TypeNode type = generateType(spec, depth, 0);
        PropertySpec property = new PropertySpec(name, type);
        property.annos.addAll(declarationConstraints(type, false));
        boolean cascadable = type.kind == Kind.BEAN
            || ((type.kind == Kind.LIST || type.kind == Kind.SET || type.kind == Kind.OPTIONAL) && type.args.get(0).kind == Kind.BEAN)
            || (type.kind == Kind.MAP && type.args.get(1).kind == Kind.BEAN)
            || (type.kind == Kind.ARRAY && type.args.get(0).kind == Kind.BEAN);
        if (cascadable && chance(0.75)) {
            property.valid = true;
            if (chance(0.15)) {
                property.convertGroup = chance(0.5) ? "from = Default.class, to = G1.class" : "from = G2.class, to = G3.class";
            }
        }
        return property;
    }

    private TypeNode generateType(CaseSpec spec, int depth, int nesting) {
        int roll = random.nextInt(100);
        if (roll < 55 || nesting > 1) {
            return TypeNode.of(SCALARS[random.nextInt(SCALARS.length)]);
        }
        if (roll < 63 && depth < 2) {
            return TypeNode.bean(generateBean(spec, depth + 1, null));
        }
        if (roll < 74) {
            return withElementConstraints(TypeNode.of(Kind.LIST, elementType(spec, depth, nesting)));
        }
        if (roll < 79) {
            return withElementConstraints(TypeNode.of(Kind.SET, elementType(spec, depth, nesting)));
        }
        if (roll < 87) {
            TypeNode key = TypeNode.of(chance(0.8) ? Kind.STRING : Kind.INTEGER);
            TypeNode value = elementType(spec, depth, nesting);
            TypeNode map = TypeNode.of(Kind.MAP, key, value);
            key.annos.addAll(typeUseConstraints(key));
            value.annos.addAll(typeUseConstraints(value));
            maybeCascadeArg(value);
            return map;
        }
        if (roll < 94) {
            TypeNode element = chance(0.2) && depth < 2 ? TypeNode.bean(generateBean(spec, depth + 1, null)) : TypeNode.of(referenceScalar());
            return withElementConstraints(TypeNode.of(Kind.OPTIONAL, element));
        }
        if (roll < 97 && depth < 2 && chance(0.5)) {
            return TypeNode.of(Kind.ARRAY, TypeNode.bean(generateBean(spec, depth + 1, null)));
        }
        if (roll < 98) {
            return TypeNode.of(Kind.INT_ARRAY);
        }
        return TypeNode.of(Kind.ARRAY, TypeNode.of(Kind.STRING));
    }

    private TypeNode elementType(CaseSpec spec, int depth, int nesting) {
        int roll = random.nextInt(100);
        if (roll < 15 && depth < 2) {
            return TypeNode.bean(generateBean(spec, depth + 1, null));
        }
        if (roll < 25 && nesting == 0) {
            TypeNode inner = TypeNode.of(chance(0.7) ? Kind.LIST : Kind.SET, TypeNode.of(Kind.STRING));
            return withElementConstraints(inner);
        }
        return TypeNode.of(referenceScalar());
    }

    private Kind referenceScalar() {
        Kind kind;
        do {
            kind = SCALARS[random.nextInt(SCALARS.length)];
        } while (kind.isPrimitive());
        return kind;
    }

    private TypeNode withElementConstraints(TypeNode container) {
        TypeNode element = container.args.get(0);
        element.annos.addAll(typeUseConstraints(element));
        maybeCascadeArg(element);
        return container;
    }

    private void maybeCascadeArg(TypeNode arg) {
        if (arg.kind == Kind.BEAN && chance(0.6)) {
            arg.valid = true;
        }
    }

    // ---------------------------------------------------------------- constraints

    private List<Anno> typeUseConstraints(TypeNode type) {
        if (!chance(0.6)) {
            return List.of();
        }
        return constraintsFor(type, 1 + random.nextInt(2), false);
    }

    private List<Anno> declarationConstraints(TypeNode type, boolean override) {
        if (!override && chance(0.2)) {
            return List.of();
        }
        TypeNode target = type;
        if (type.kind == Kind.OPTIONAL && chance(0.7)) {
            // a declaration constraint of an Optional applies to the unwrapped value
            target = type.args.get(0);
            List<Anno> result = constraintsFor(target, 1 + random.nextInt(2), true);
            if (chance(0.3)) {
                result = new ArrayList<>(result);
                result.add(new Anno("NotNull"));
            }
            return result;
        }
        return constraintsFor(target, 1 + random.nextInt(3), false);
    }

    private List<Anno> constraintsFor(TypeNode type, int count, boolean unwrapped) {
        List<Anno> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Anno anno = constraint(type);
            if (anno != null && result.stream().noneMatch(a -> a.name().equals(anno.name()))) {
                maybeMessage(anno);
                maybeGroups(anno);
                if (chance(0.03)) {
                    anno.with("payload", "FuzzPayload.class");
                }
                result.add(anno);
            }
        }
        return result;
    }

    private Anno constraint(TypeNode type) {
        Kind kind = type.kind;
        if (chance(0.02) && kind != Kind.BEAN) {
            // knowingly unsupported combination, checked by both implementations
            String[] any = {"NotBlank", "Size", "Min", "Past", "AssertTrue", "Email", "Positive", "Digits", "DecimalMax"};
            Anno anno = new Anno(any[random.nextInt(any.length)], new java.util.LinkedHashMap<>(), false, true);
            switch (anno.name()) {
                case "Min" -> anno.with("value", "1");
                case "Digits" -> anno.with("integer", "2").with("fraction", "0");
                case "DecimalMax" -> anno.with("value", "\"5\"");
                default -> { }
            }
            return supports(kind, anno.name()) ? null : anno;
        }
        List<String> options = new ArrayList<>();
        if (!kind.isPrimitive()) {
            options.add("NotNull");
            options.add("NotNull");
            options.add("Null");
        }
        if (kind.isCharSequence()) {
            options.addAll(List.of("NotBlank", "NotEmpty", "Size", "Pattern", "Pattern", "Email", "Email", "DecimalMin", "Digits", "FuzzCheck", "FuzzComposed", "FuzzComposedSingle", "FuzzLength"));
        }
        if (kind == Kind.LIST || kind == Kind.SET || kind == Kind.MAP || kind == Kind.ARRAY || kind == Kind.INT_ARRAY) {
            options.addAll(List.of("NotEmpty", "Size", "Size"));
        }
        if (kind.isIntegral() || kind.isDecimal()) {
            options.addAll(List.of("Min", "Max", "DecimalMin", "DecimalMax", "Digits", "Positive", "PositiveOrZero", "Negative", "NegativeOrZero"));
        }
        if (kind.isFloating()) {
            options.addAll(List.of("Positive", "PositiveOrZero", "Negative", "NegativeOrZero"));
        }
        if (kind.isTemporal()) {
            options.addAll(List.of("Past", "PastOrPresent", "Future", "FutureOrPresent"));
        }
        if (kind.isBoolean()) {
            options.addAll(List.of("AssertTrue", "AssertFalse"));
        }
        if (options.isEmpty()) {
            return null;
        }
        String name = options.get(random.nextInt(options.size()));
        Anno anno = new Anno(name);
        switch (name) {
            case "Size" -> {
                int min = random.nextInt(4);
                int max = min + random.nextInt(4);
                if (chance(0.03)) {
                    anno = new Anno(name, new java.util.LinkedHashMap<>(), true, false);
                    anno.with("min", String.valueOf(max + 1)).with("max", String.valueOf(min));
                } else if (chance(0.02)) {
                    anno = new Anno(name, new java.util.LinkedHashMap<>(), true, false);
                    anno.with("min", "-1");
                } else {
                    if (min > 0 || chance(0.5)) {
                        anno.with("min", String.valueOf(min));
                    }
                    if (chance(0.8)) {
                        anno.with("max", String.valueOf(max));
                    }
                }
            }
            case "Pattern" -> {
                if (chance(0.02)) {
                    anno = new Anno(name, new java.util.LinkedHashMap<>(), true, false);
                    anno.with("regexp", "\"[unclosed\"");
                } else {
                    String[] p = PATTERNS[random.nextInt(PATTERNS.length)];
                    anno.with("regexp", p[0]);
                    if (chance(0.25)) {
                        anno.with("flags", chance(0.5) ? "Pattern.Flag.CASE_INSENSITIVE"
                            : "{Pattern.Flag.CASE_INSENSITIVE, Pattern.Flag.UNICODE_CASE}");
                    }
                }
            }
            case "Email" -> {
                if (chance(0.25)) {
                    anno.with("regexp", chance(0.5) ? "\".*@example\\\\.com\"" : "\"[a-z]+@.*\"");
                    if (chance(0.3)) {
                        anno.with("flags", "Pattern.Flag.CASE_INSENSITIVE");
                    }
                }
            }
            case "Min", "Max" -> anno.with("value", String.valueOf(bound()) + "L");
            case "DecimalMin", "DecimalMax" -> {
                if (chance(0.02)) {
                    anno = new Anno(name, new java.util.LinkedHashMap<>(), true, false);
                    anno.with("value", "\"abc\"");
                } else {
                    String[] values = {"0", "0.5", "-1", "10", "1e2", "-0.001", "100.25", "5"};
                    anno.with("value", "\"" + values[random.nextInt(values.length)] + "\"");
                    if (chance(0.4)) {
                        anno.with("inclusive", String.valueOf(random.nextBoolean()));
                    }
                }
            }
            case "Digits" -> {
                if (chance(0.02)) {
                    anno = new Anno(name, new java.util.LinkedHashMap<>(), true, false);
                    anno.with("integer", "-1").with("fraction", "0");
                } else {
                    anno.with("integer", String.valueOf(random.nextInt(4))).with("fraction", String.valueOf(random.nextInt(3)));
                }
            }
            case "FuzzCheck" -> anno.with("minLength", String.valueOf(random.nextInt(4)));
            case "FuzzLength" -> anno.with("max", String.valueOf(1 + random.nextInt(5)));
            case "FuzzComposed", "FuzzComposedSingle" -> {
                if (chance(0.5)) {
                    anno.with("max", String.valueOf(2 + random.nextInt(4)));
                }
            }
            default -> { }
        }
        return anno;
    }

    private static boolean supports(Kind kind, String constraint) {
        return switch (constraint) {
            case "NotBlank", "Email" -> kind.isCharSequence();
            case "Size" -> kind.isSizeable();
            case "Min", "Positive" -> kind.isNumeric();
            case "Digits", "DecimalMax" -> kind.isIntegral() || kind.isDecimal() || kind.isCharSequence();
            case "Past" -> kind.isTemporal();
            case "AssertTrue" -> kind.isBoolean();
            default -> true;
        };
    }

    private long bound() {
        long[] bounds = {0, 1, 2, 5, 10, -1, -3, 100, 127, -128, 32767, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE};
        return bounds[random.nextInt(bounds.length)];
    }

    private void maybeMessage(Anno anno) {
        if (chance(0.12)) {
            anno.with("message", MESSAGES[random.nextInt(MESSAGES.length)]);
        }
    }

    private void maybeGroups(Anno anno) {
        if (chance(0.25)) {
            String[] options = {"G1.class", "G2.class", "G3.class", "{G1.class, G3.class}", "{Default.class, G2.class}", "{}"};
            anno.with("groups", options[random.nextInt(options.length)]);
        }
    }

    // ---------------------------------------------------------------- values

    /**
     * The expression building an instance. Each property value is enclosed in comment markers naming the property,
     * so that the minimizer can remove a property from the instances.
     */
    String instance(BeanSpec bean, int depth) {
        List<PropertySpec> properties = bean.allProperties();
        if (bean.record) {
            return "new " + bean.name + "(" + properties.stream()
                .map(p -> open(bean, p) + value(p.type, depth, p) + close(bean, p))
                .collect(Collectors.joining(", ")) + ")";
        }
        String var = "b" + depth;
        StringBuilder sb = new StringBuilder("with(new " + bean.name + "(), " + var + " -> {");
        for (PropertySpec p : properties) {
            if (chance(0.1) && !p.type.kind.isPrimitive()) {
                continue; // left at its default
            }
            sb.append(' ').append(open(bean, p)).append(var).append('.').append(p.setterName()).append("(")
                .append(value(p.type, depth, p)).append(");").append(close(bean, p));
        }
        return sb.append(" })").toString();
    }

    static String open(BeanSpec bean, PropertySpec p) {
        return "/*<" + owner(bean, p) + "." + p.name + ">*/";
    }

    static String close(BeanSpec bean, PropertySpec p) {
        return "/*</" + owner(bean, p) + "." + p.name + ">*/";
    }

    private static String owner(BeanSpec bean, PropertySpec p) {
        BeanSpec b = bean;
        while (b != null && !b.properties.contains(p)) {
            b = b.parent;
        }
        return b == null ? bean.name : b.name;
    }

    /**
     * Removes the value of a property from an instance expression.
     */
    static String withoutProperty(String instance, String owner, String property) {
        String tag = owner + "." + property;
        String result = instance.replaceAll(java.util.regex.Pattern.quote("/*<" + tag + ">*/") + ".*?"
            + java.util.regex.Pattern.quote("/*</" + tag + ">*/"), "");
        // the arguments of a record constructor
        String previous;
        do {
            previous = result;
            result = result.replaceAll("\\(\\s*,\\s*", "(").replaceAll(",\\s*,", ",").replaceAll(",\\s*\\)", ")");
        } while (!previous.equals(result));
        return result;
    }

    private String value(TypeNode type, int depth, PropertySpec property) {
        if (!type.kind.isPrimitive() && chance(0.12)) {
            return type.kind == Kind.BEAN || type.kind.isContainer() || type.kind == Kind.INT_ARRAY
                ? "null" : "(" + type.render(false, false) + ") null";
        }
        List<Anno> constraints = new ArrayList<>(type.annos);
        if (property != null) {
            constraints.addAll(property.annos);
        }
        return switch (type.kind) {
            case STRING, CHAR_SEQUENCE -> stringValue(constraints);
            case INT -> String.valueOf(integral(constraints, Integer.MIN_VALUE, Integer.MAX_VALUE));
            case INTEGER -> "Integer.valueOf(" + integral(constraints, Integer.MIN_VALUE, Integer.MAX_VALUE) + ")";
            case LONG -> integral(constraints, Long.MIN_VALUE, Long.MAX_VALUE) + "L";
            case LONG_W -> "Long.valueOf(" + integral(constraints, Long.MIN_VALUE, Long.MAX_VALUE) + "L)";
            case SHORT_W -> "Short.valueOf((short) " + integral(constraints, Short.MIN_VALUE, Short.MAX_VALUE) + ")";
            case BYTE_W -> "Byte.valueOf((byte) " + integral(constraints, Byte.MIN_VALUE, Byte.MAX_VALUE) + ")";
            case DOUBLE -> floating();
            case DOUBLE_W -> "Double.valueOf(" + floating() + ")";
            case FLOAT_W -> "Float.valueOf((float) " + floating() + ")";
            case BIG_DECIMAL -> "new BigDecimal(\"" + decimalLiteral(constraints) + "\")";
            case BIG_INTEGER -> "new BigInteger(\"" + integral(constraints, Long.MIN_VALUE, Long.MAX_VALUE) + (chance(0.1) ? "000000000000" : "") + "\")";
            case BOOLEAN -> String.valueOf(random.nextBoolean());
            case BOOLEAN_W -> "Boolean.valueOf(" + random.nextBoolean() + ")";
            case LOCAL_DATE -> pick("LocalDate.now()", "LocalDate.now().minusDays(1)", "LocalDate.now().plusDays(1)", "LocalDate.of(1990, 1, 1)", "LocalDate.of(2999, 12, 31)");
            case LOCAL_DATE_TIME -> pick("LocalDateTime.now().minusHours(1)", "LocalDateTime.now().plusHours(1)", "LocalDateTime.of(2000, 1, 1, 0, 0)");
            case INSTANT -> pick("Instant.now().minusSeconds(3600)", "Instant.now().plusSeconds(3600)", "Instant.EPOCH", "Instant.parse(\"3000-01-01T00:00:00Z\")");
            case ZONED_DATE_TIME -> pick("ZonedDateTime.now().minusDays(1)", "ZonedDateTime.now(ZoneId.of(\"Pacific/Kiritimati\")).plusHours(1)", "ZonedDateTime.now(ZoneId.of(\"Pacific/Kiritimati\")).minusHours(1)", "ZonedDateTime.now(ZoneId.of(\"America/Los_Angeles\")).plusMinutes(30)");
            case OFFSET_DATE_TIME -> pick("OffsetDateTime.now().minusDays(1)", "OffsetDateTime.now(ZoneOffset.ofHours(14)).plusMinutes(30)", "OffsetDateTime.now(ZoneOffset.ofHours(-12)).minusMinutes(30)");
            case DATE -> pick("new Date(System.currentTimeMillis() - 86400000L)", "new Date(System.currentTimeMillis() + 86400000L)", "new Date(0L)");
            case YEAR -> pick("Year.now()", "Year.now().minusYears(1)", "Year.now().plusYears(1)");
            case YEAR_MONTH -> pick("YearMonth.now()", "YearMonth.now().minusMonths(1)", "YearMonth.now().plusMonths(1)");
            case ENUM -> pick("FuzzColor.RED", "FuzzColor.GREEN");
            case LIST -> "list(" + elements(type.args.get(0), depth, constraints) + ")";
            case SET -> "set(" + elements(type.args.get(0), depth, constraints) + ")";
            case MAP -> mapValue(type, depth, constraints);
            case OPTIONAL -> optionalValue(type, depth, property);
            case INT_ARRAY -> "new int[] {" + repeat(sizeFor(constraints), () -> String.valueOf(random.nextInt(20) - 5)) + "}";
            case ARRAY -> "new " + type.args.get(0).render(false, false) + "[] {" + elements(type.args.get(0), depth, constraints) + "}";
            case BEAN -> depth > 3 ? "null" : instance(type.bean, depth + 1);
        };
    }

    private String optionalValue(TypeNode type, int depth, PropertySpec property) {
        if (chance(0.25)) {
            return "Optional.empty()";
        }
        TypeNode element = type.args.get(0);
        String inner = value(element, depth, null);
        if (inner.equals("null") || inner.endsWith(") null")) {
            return "Optional.empty()";
        }
        if (property != null && !property.annos.isEmpty() && element.kind.isCharSequence()) {
            inner = stringValue(property.annos);
        }
        return "Optional.of(" + inner + ")";
    }

    private String mapValue(TypeNode type, int depth, List<Anno> constraints) {
        int size = sizeFor(constraints);
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            TypeNode key = type.args.get(0);
            String k = key.kind == Kind.STRING ? (chance(0.15) ? "(String) null" : stringValue(key.annos)) : "Integer.valueOf(" + random.nextInt(5) + ")";
            entries.add(k);
            entries.add(value(type.args.get(1), depth, null));
        }
        return "map(" + String.join(", ", entries) + ")";
    }

    private String elements(TypeNode element, int depth, List<Anno> constraints) {
        return repeat(sizeFor(constraints), () -> value(element, depth, null));
    }

    private int sizeFor(List<Anno> constraints) {
        for (Anno a : constraints) {
            if (a.name().equals("Size") && chance(0.6)) {
                int min = parseInt(a.members().get("min"), 0);
                int max = parseInt(a.members().get("max"), 4);
                int[] candidates = {Math.max(0, min - 1), min, max, max + 1};
                return Math.min(5, candidates[random.nextInt(candidates.length)]);
            }
        }
        return random.nextInt(4);
    }

    private String stringValue(List<Anno> constraints) {
        if (!constraints.isEmpty() && chance(0.6)) {
            Anno a = constraints.get(random.nextInt(constraints.size()));
            switch (a.name()) {
                case "Size", "FuzzComposed", "FuzzComposedSingle", "FuzzLength" -> {
                    int min = parseInt(a.members().get("min"), a.name().startsWith("FuzzComposed") ? 2 : 0);
                    int max = parseInt(a.members().get("max"), a.name().startsWith("FuzzComposed") ? 5 : 4);
                    int[] candidates = {Math.max(0, min - 1), min, max, max + 1};
                    return lit("x".repeat(Math.min(20, Math.max(0, candidates[random.nextInt(candidates.length)]))));
                }
                case "Pattern" -> {
                    for (String[] p : PATTERNS) {
                        if (p[0].equals(a.members().get("regexp"))) {
                            String s = chance(0.5) ? p[1] : p[2];
                            if (s != null) {
                                return lit(s.equals(p[1]) && chance(0.3) ? s.toUpperCase() : s);
                            }
                        }
                    }
                }
                case "DecimalMin", "DecimalMax", "Digits" -> {
                    return lit(pick("0", "5", "10", "-1", "0.5", "100.25", "1e2", "abc", "12.345", "-0.001", "1000"));
                }
                default -> { }
            }
        }
        return lit(STRINGS[random.nextInt(STRINGS.length)]);
    }

    private long integral(List<Anno> constraints, long min, long max) {
        long base = 0;
        boolean found = false;
        for (Anno a : constraints) {
            String v = a.members().get("value");
            if (v != null && (a.name().equals("Min") || a.name().equals("Max"))) {
                base = Long.parseLong(v.replace("L", ""));
                found = true;
            } else if (v != null && (a.name().equals("DecimalMin") || a.name().equals("DecimalMax"))) {
                try {
                    base = new java.math.BigDecimal(v.replace("\"", "")).longValue();
                    found = true;
                } catch (NumberFormatException e) {
                    // a knowingly invalid definition
                }
            }
        }
        long value;
        if (found && chance(0.7)) {
            long[] offsets = {-1, 0, 1};
            long off = offsets[random.nextInt(offsets.length)];
            value = base + off;
            if ((off > 0 && value < base) || (off < 0 && value > base)) {
                value = base; // overflow
            }
        } else {
            long[] values = {0, 1, -1, 2, 5, 10, 99, 100, 101, 1000, -50, 12345};
            value = values[random.nextInt(values.length)];
        }
        return Math.max(min, Math.min(max, value));
    }

    private String floating() {
        return pick("0.0", "-0.0", "1.5", "-1.5", "0.0001", "-0.0001", "1.0E10", "Double.NaN", "Double.POSITIVE_INFINITY", "Double.NEGATIVE_INFINITY", "Double.MIN_VALUE");
    }

    private String decimalLiteral(List<Anno> constraints) {
        for (Anno a : constraints) {
            String v = a.members().get("value");
            if (v != null && chance(0.6)) {
                String raw = v.replace("\"", "").replace("L", "");
                try {
                    java.math.BigDecimal b = new java.math.BigDecimal(raw);
                    java.math.BigDecimal[] candidates = {b, b.add(new java.math.BigDecimal("0.001")), b.subtract(new java.math.BigDecimal("0.001")), b.setScale(3)};
                    return candidates[random.nextInt(candidates.length)].toPlainString();
                } catch (NumberFormatException | ArithmeticException e) {
                    break;
                }
            }
        }
        return pick("0", "0.0", "-0.00", "1.5", "-1.5", "10", "10.001", "99.99", "123.456", "1E+2", "0.0001", "-100");
    }

    private String repeat(int n, java.util.function.Supplier<String> s) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(s.get());
        }
        return String.join(", ", out);
    }

    private static int parseInt(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String lit(String s) {
        return "\"" + s + "\"";
    }

    private String pick(String... options) {
        return options[random.nextInt(options.length)];
    }

    private boolean chance(double p) {
        return random.nextDouble() < p;
    }
}
