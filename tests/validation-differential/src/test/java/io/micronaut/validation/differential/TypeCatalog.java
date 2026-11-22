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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The value types the random generator declares, the Jakarta Validation 3.1 built-in constraints the specification
 * says each type supports, and edge-case Java expressions for each type. The fixed clock is 2026-01-15T12:00:00Z UTC.
 */
final class TypeCatalog {
    /** Constraint families, each mapped to the types the specification lists as supported. */
    enum Family { BOOLEAN, INTEGRAL, FLOATING, BIG, CHARS, SIZED, TEMPORAL }

    /** A declarable scalar type. */
    record Scalar(String name, boolean primitive, Set<Family> families, List<String> values) {
        String boxed() {
            return switch (name) {
                case "int" -> "Integer";
                case "long" -> "Long";
                case "short" -> "Short";
                case "byte" -> "Byte";
                case "double" -> "Double";
                case "float" -> "Float";
                case "boolean" -> "Boolean";
                default -> name;
            };
        }

        /** Values including {@code null} for reference types. */
        List<String> valuesWithNull() {
            if (primitive) {
                return values;
            }
            List<String> all = new ArrayList<>(values);
            all.add("null");
            return all;
        }
    }

    static final String NOW_MILLIS = "1768478400000L";

    static final List<Scalar> SCALARS = List.of(
        new Scalar("boolean", true, EnumSet.of(Family.BOOLEAN), List.of("true", "false")),
        new Scalar("Boolean", false, EnumSet.of(Family.BOOLEAN), List.of("true", "false")),
        new Scalar("int", true, EnumSet.of(Family.INTEGRAL), List.of("0", "1", "-1", "5", "127", "128", "Integer.MIN_VALUE", "Integer.MAX_VALUE")),
        new Scalar("Integer", false, EnumSet.of(Family.INTEGRAL), List.of("0", "1", "-1", "5", "100", "Integer.MIN_VALUE", "Integer.MAX_VALUE")),
        new Scalar("long", true, EnumSet.of(Family.INTEGRAL), List.of("0L", "1L", "-1L", "5L", "Long.MIN_VALUE", "Long.MAX_VALUE", "12345L")),
        new Scalar("Long", false, EnumSet.of(Family.INTEGRAL), List.of("0L", "1L", "-1L", "Long.MIN_VALUE", "Long.MAX_VALUE", "99L")),
        new Scalar("short", true, EnumSet.of(Family.INTEGRAL), List.of("(short) 0", "(short) -1", "(short) 5", "Short.MIN_VALUE", "Short.MAX_VALUE")),
        new Scalar("Byte", false, EnumSet.of(Family.INTEGRAL), List.of("(byte) 0", "(byte) -1", "(byte) 5", "Byte.MIN_VALUE", "Byte.MAX_VALUE")),
        new Scalar("double", true, EnumSet.of(Family.FLOATING), List.of("0.0d", "-0.0d", "1.5d", "-1e-300d", "Double.NaN", "Double.POSITIVE_INFINITY",
            "Double.NEGATIVE_INFINITY", "Double.MIN_VALUE")),
        new Scalar("Float", false, EnumSet.of(Family.FLOATING), List.of("0.0f", "-0.0f", "2.5f", "-1e-40f", "Float.NaN", "Float.NEGATIVE_INFINITY")),
        new Scalar("BigDecimal", false, EnumSet.of(Family.BIG), List.of("new java.math.BigDecimal(\"0\")",
            "new java.math.BigDecimal(\"-0.00\")", "new java.math.BigDecimal(\"1E+3\")", "new java.math.BigDecimal(\"123.4500\")",
            "new java.math.BigDecimal(\"-1E-10\")", "new java.math.BigDecimal(\"99999999999999999999.5\")",
            "new java.math.BigDecimal(\"0.0001\")", "new java.math.BigDecimal(\"5\")", "new java.math.BigDecimal(\"10.0\")",
            "new java.math.BigDecimal(\"9223372036854775808\")")),
        new Scalar("BigInteger", false, EnumSet.of(Family.BIG), List.of("java.math.BigInteger.ZERO", "java.math.BigInteger.ONE.negate()",
            "java.math.BigInteger.TEN", "new java.math.BigInteger(\"9223372036854775808\")", "new java.math.BigInteger(\"-9223372036854775809\")",
            "new java.math.BigInteger(\"128\")")),
        new Scalar("String", false, EnumSet.of(Family.CHARS), List.of("\"\"", "\" \"", "\"\\t\\n\"", "\"abc\"", "\"ABC\"", "\"a\"", "\"12\"",
            "\"12.345\"", "\"-0\"", "\"1e3\"", "\"0.0001\"", "\"a@b.c\"", "\"a@b\"", "\"\\\"q u\\\"@x.com\"", "\"user@[127.0.0.1]\"",
            "\"a..b@c.com\"", "\"\\u00e9\\ud834\\udd1e\"", "\"a\\nb\"", "\"abcdef\"", "\"x@y.com\"", "\"-9223372036854775809\"", "\"99.99\"",
            "\" 12 \"", "\"\\u2003\"", "\"+5\"", "\".5\"", "\"x@-y.com\"", "\"\\u00e4@\\u00f6.de\"")),
        new Scalar("CharSequence", false, EnumSet.of(Family.CHARS), List.of("new StringBuilder(\"12.3\")", "new StringBuilder()",
            "new StringBuilder(\"ab c\")", "new StringBuilder(\" \")", "new StringBuilder(\"a@b.com\")")),
        new Scalar("Instant", false, EnumSet.of(Family.TEMPORAL), List.of("java.time.Instant.ofEpochMilli(" + NOW_MILLIS + ")",
            "java.time.Instant.ofEpochMilli(" + NOW_MILLIS + ").plusNanos(1)", "java.time.Instant.ofEpochMilli(" + NOW_MILLIS + ").minusNanos(1)",
            "java.time.Instant.ofEpochMilli(" + NOW_MILLIS + ").plusSeconds(86400)", "java.time.Instant.MIN", "java.time.Instant.MAX")),
        new Scalar("LocalDate", false, EnumSet.of(Family.TEMPORAL), List.of("java.time.LocalDate.of(2026, 1, 15)",
            "java.time.LocalDate.of(2026, 1, 14)", "java.time.LocalDate.of(2026, 1, 16)", "java.time.LocalDate.MIN", "java.time.LocalDate.MAX")),
        new Scalar("LocalDateTime", false, EnumSet.of(Family.TEMPORAL), List.of("java.time.LocalDateTime.of(2026, 1, 15, 12, 0)",
            "java.time.LocalDateTime.of(2026, 1, 15, 12, 0, 0, 1)", "java.time.LocalDateTime.of(2026, 1, 15, 11, 59, 59, 999999999)",
            "java.time.LocalDateTime.MIN")),
        new Scalar("LocalTime", false, EnumSet.of(Family.TEMPORAL), List.of("java.time.LocalTime.NOON",
            "java.time.LocalTime.of(12, 0, 0, 1)", "java.time.LocalTime.of(11, 59, 59, 999999999)", "java.time.LocalTime.MIDNIGHT",
            "java.time.LocalTime.MAX")),
        new Scalar("OffsetDateTime", false, EnumSet.of(Family.TEMPORAL), List.of(
            "java.time.OffsetDateTime.of(2026, 1, 15, 12, 0, 0, 0, java.time.ZoneOffset.UTC)",
            "java.time.OffsetDateTime.of(2026, 1, 15, 14, 0, 0, 0, java.time.ZoneOffset.ofHours(2))",
            "java.time.OffsetDateTime.of(2026, 1, 15, 13, 0, 0, 0, java.time.ZoneOffset.ofHours(2))",
            "java.time.OffsetDateTime.of(2026, 1, 15, 11, 0, 0, 0, java.time.ZoneOffset.ofHours(-2))")),
        new Scalar("ZonedDateTime", false, EnumSet.of(Family.TEMPORAL), List.of(
            "java.time.ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, java.time.ZoneOffset.UTC)",
            "java.time.ZonedDateTime.of(2026, 1, 15, 13, 0, 0, 0, java.time.ZoneId.of(\"Europe/Paris\"))",
            "java.time.ZonedDateTime.of(2026, 1, 15, 13, 0, 0, 1, java.time.ZoneId.of(\"Europe/Paris\"))",
            "java.time.ZonedDateTime.of(2026, 1, 15, 6, 59, 59, 0, java.time.ZoneId.of(\"America/New_York\"))")),
        new Scalar("OffsetTime", false, EnumSet.of(Family.TEMPORAL), List.of("java.time.OffsetTime.of(12, 0, 0, 0, java.time.ZoneOffset.UTC)",
            "java.time.OffsetTime.of(13, 0, 0, 0, java.time.ZoneOffset.ofHours(1))", "java.time.OffsetTime.of(12, 0, 0, 0, java.time.ZoneOffset.ofHours(1))",
            "java.time.OffsetTime.of(12, 0, 0, 0, java.time.ZoneOffset.ofHours(-1))")),
        new Scalar("Year", false, EnumSet.of(Family.TEMPORAL), List.of("java.time.Year.of(2026)", "java.time.Year.of(2025)",
            "java.time.Year.of(2027)")),
        new Scalar("YearMonth", false, EnumSet.of(Family.TEMPORAL), List.of("java.time.YearMonth.of(2026, 1)",
            "java.time.YearMonth.of(2025, 12)", "java.time.YearMonth.of(2026, 2)")),
        new Scalar("MonthDay", false, EnumSet.of(Family.TEMPORAL), List.of("java.time.MonthDay.of(1, 15)", "java.time.MonthDay.of(1, 14)",
            "java.time.MonthDay.of(1, 16)", "java.time.MonthDay.of(2, 29)")),
        new Scalar("Date", false, EnumSet.of(Family.TEMPORAL), List.of("new java.util.Date(" + NOW_MILLIS + ")",
            "new java.util.Date(" + NOW_MILLIS + " + 1)", "new java.util.Date(" + NOW_MILLIS + " - 1)", "new java.util.Date(0L)")),
        new Scalar("Calendar", false, EnumSet.of(Family.TEMPORAL), List.of("calendar(0)", "calendar(1)", "calendar(-1)",
            "calendar(-86400000L)")),
        new Scalar("HijrahDate", false, EnumSet.of(Family.TEMPORAL), List.of(
            "java.time.chrono.HijrahDate.from(java.time.LocalDate.of(2026, 1, 15))", "java.time.chrono.HijrahDate.from(java.time.LocalDate.of(2026, 1, 16))")),
        new Scalar("JapaneseDate", false, EnumSet.of(Family.TEMPORAL), List.of(
            "java.time.chrono.JapaneseDate.from(java.time.LocalDate.of(2026, 1, 15))", "java.time.chrono.JapaneseDate.from(java.time.LocalDate.of(2026, 1, 14))"))
    );

    private TypeCatalog() { }
}
