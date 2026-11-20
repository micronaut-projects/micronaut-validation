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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The model of a generated fuzz case: bean types, their properties and constraints.
 */
final class Model {

    private Model() {
    }

    /**
     * The kinds of value types the generator uses.
     */
    enum Kind {
        STRING("String"),
        CHAR_SEQUENCE("CharSequence"),
        INT("int"),
        INTEGER("Integer"),
        LONG("long"),
        LONG_W("Long"),
        SHORT_W("Short"),
        BYTE_W("Byte"),
        DOUBLE("double"),
        DOUBLE_W("Double"),
        FLOAT_W("Float"),
        BIG_DECIMAL("BigDecimal"),
        BIG_INTEGER("BigInteger"),
        BOOLEAN("boolean"),
        BOOLEAN_W("Boolean"),
        LOCAL_DATE("LocalDate"),
        LOCAL_DATE_TIME("LocalDateTime"),
        INSTANT("Instant"),
        ZONED_DATE_TIME("ZonedDateTime"),
        OFFSET_DATE_TIME("OffsetDateTime"),
        DATE("Date"),
        YEAR("Year"),
        YEAR_MONTH("YearMonth"),
        ENUM("FuzzColor"),
        LIST("List"),
        SET("Set"),
        MAP("Map"),
        OPTIONAL("Optional"),
        ARRAY("[]"),
        INT_ARRAY("int[]"),
        BEAN("bean");

        final String src;

        Kind(String src) {
            this.src = src;
        }

        boolean isCharSequence() {
            return this == STRING || this == CHAR_SEQUENCE;
        }

        boolean isIntegral() {
            return switch (this) {
                case INT, INTEGER, LONG, LONG_W, SHORT_W, BYTE_W, BIG_INTEGER -> true;
                default -> false;
            };
        }

        boolean isDecimal() {
            return this == BIG_DECIMAL;
        }

        boolean isFloating() {
            return this == DOUBLE || this == DOUBLE_W || this == FLOAT_W;
        }

        boolean isNumeric() {
            return isIntegral() || isDecimal() || isFloating();
        }

        boolean isTemporal() {
            return switch (this) {
                case LOCAL_DATE, LOCAL_DATE_TIME, INSTANT, ZONED_DATE_TIME, OFFSET_DATE_TIME, DATE, YEAR, YEAR_MONTH -> true;
                default -> false;
            };
        }

        boolean isBoolean() {
            return this == BOOLEAN || this == BOOLEAN_W;
        }

        boolean isPrimitive() {
            return this == INT || this == LONG || this == DOUBLE || this == BOOLEAN;
        }

        boolean isContainer() {
            return this == LIST || this == SET || this == MAP || this == OPTIONAL || this == ARRAY;
        }

        boolean isSizeable() {
            return isCharSequence() || this == LIST || this == SET || this == MAP || this == ARRAY || this == INT_ARRAY;
        }
    }

    /**
     * A constraint (or other) annotation with its members rendered as Java source.
     *
     * @param name The simple name of the annotation
     * @param members The members, rendered
     * @param definitionError Whether the generator knowingly produced an invalid definition
     * @param unsupportedType Whether the generator knowingly placed the constraint on an unsupported type
     */
    record Anno(String name, Map<String, String> members, boolean definitionError, boolean unsupportedType) {

        Anno(String name) {
            this(name, new LinkedHashMap<>(), false, false);
        }

        Anno with(String member, String value) {
            members.put(member, value);
            return this;
        }

        String render() {
            if (members.isEmpty()) {
                return "@" + name;
            }
            return "@" + name + "(" + members.entrySet().stream()
                .map(e -> e.getKey() + " = " + e.getValue())
                .collect(Collectors.joining(", ")) + ")";
        }
    }

    /**
     * A type, possibly with type-use constraints.
     */
    static final class TypeNode {
        final Kind kind;
        final List<TypeNode> args;
        final BeanSpec bean;
        final List<Anno> annos = new ArrayList<>();
        boolean valid;

        TypeNode(Kind kind, List<TypeNode> args, BeanSpec bean) {
            this.kind = kind;
            this.args = args;
            this.bean = bean;
        }

        static TypeNode of(Kind kind, TypeNode... args) {
            return new TypeNode(kind, List.of(args), null);
        }

        static TypeNode bean(BeanSpec bean) {
            return new TypeNode(Kind.BEAN, List.of(), bean);
        }

        /**
         * Renders the type. The annotations of this node are rendered only when {@code withOwn}; those of the
         * type arguments whenever {@code withArgs}.
         */
        String render(boolean withOwn, boolean withArgs) {
            String prefix = withOwn ? own() : "";
            return switch (kind) {
                case LIST, SET, OPTIONAL -> prefix + kind.src + "<" + args.get(0).render(withArgs, withArgs) + ">";
                case MAP -> prefix + "Map<" + args.get(0).render(withArgs, withArgs) + ", " + args.get(1).render(withArgs, withArgs) + ">";
                case ARRAY -> args.get(0).render(false, withArgs) + (withOwn ? " " + own() : "") + "[]";
                case BEAN -> prefix + bean.name;
                default -> prefix + kind.src;
            };
        }

        /** The erased-ish description used to group differences. */
        String describe() {
            return switch (kind) {
                case LIST, SET, OPTIONAL -> kind.src + "<" + args.get(0).describe() + ">";
                case MAP -> "Map<" + args.get(0).describe() + "," + args.get(1).describe() + ">";
                case ARRAY -> args.get(0).describe() + "[]";
                case BEAN -> "Bean";
                default -> kind.src;
            };
        }

        private String own() {
            StringBuilder sb = new StringBuilder();
            if (valid) {
                sb.append("@Valid ");
            }
            for (Anno a : annos) {
                sb.append(a.render()).append(' ');
            }
            return sb.toString();
        }
    }

    /**
     * A property of a generated bean.
     */
    static final class PropertySpec {
        final String name;
        final TypeNode type;
        /** Declaration constraints, rendered on the field or the getter. */
        final List<Anno> annos = new ArrayList<>();
        boolean valid;
        String convertGroup;
        /** Constraints added by an overriding getter in a subclass. */
        PropertySpec overriddenBy;

        PropertySpec(String name, TypeNode type) {
            this.name = name;
            this.type = type;
        }

        String getterName() {
            String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            return (type.kind == Kind.BOOLEAN && name.hashCode() % 2 == 0 ? "is" : "get") + cap;
        }

        String setterName() {
            return "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }

    /**
     * A generated bean type.
     */
    static final class BeanSpec {
        final String name;
        final boolean record;
        final boolean getterConstraints;
        final BeanSpec parent;
        final List<PropertySpec> properties = new ArrayList<>();
        final List<Anno> classAnnos = new ArrayList<>();
        /** Properties of the parent whose getters this subclass overrides with additional constraints. */
        final List<PropertySpec> overrides = new ArrayList<>();
        boolean redefinesDefaultGroup;

        BeanSpec(String name, boolean record, boolean getterConstraints, BeanSpec parent) {
            this.name = name;
            this.record = record;
            this.getterConstraints = getterConstraints;
            this.parent = parent;
        }

        List<PropertySpec> allProperties() {
            List<PropertySpec> all = new ArrayList<>();
            if (parent != null) {
                all.addAll(parent.allProperties());
            }
            all.addAll(properties);
            return all;
        }
    }

    /**
     * A case: a root bean with the types it reaches, and the instances to validate.
     */
    static final class CaseSpec {
        final int id;
        final List<BeanSpec> beans = new ArrayList<>();
        BeanSpec root;
        final List<String> instances = new ArrayList<>();

        CaseSpec(int id) {
            this.id = id;
        }
    }
}
