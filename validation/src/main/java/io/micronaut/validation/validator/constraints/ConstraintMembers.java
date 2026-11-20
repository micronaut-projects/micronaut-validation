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
package io.micronaut.validation.validator.constraints;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.naming.NameUtils;
import jakarta.validation.ValidationException;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * The members the built-in validators read of an occurrence, converted once per occurrence.
 *
 * @since 5.3.0
 */
final class ConstraintMembers {

    private static final AnnotationValueCache<Size> SIZES = new AnnotationValueCache<>(value -> new Size(
        value.intValue("min").orElse(0), value.intValue("max").orElse(Integer.MAX_VALUE)));
    private static final AnnotationValueCache<Digits> DIGITS = new AnnotationValueCache<>(value -> new Digits(
        value.intValue("integer").orElse(0), value.intValue("fraction").orElse(0)));
    private static final AnnotationValueCache<DecimalLimit> DECIMAL_LIMITS = new AnnotationValueCache<>(value -> new DecimalLimit(
        value.stringValue().map(DecimalBound::parse)
            .orElseThrow(() -> new ValidationException("null does not represent a valid BigDecimal format.")),
        value.booleanValue("inclusive").orElse(true)));
    private static final AnnotationValueCache<LongBound> LONG_BOUNDS = new AnnotationValueCache<>(value -> {
        long bound = value.longValue(AnnotationMetadata.VALUE_MEMBER).orElseThrow(() ->
            new ValidationException("@" + NameUtils.getSimpleName(value.getAnnotationName()) + " annotation specified without value"));
        return new LongBound(bound, BigInteger.valueOf(bound), BigDecimal.valueOf(bound));
    });
    private static final AnnotationValueCache<UrlParts> URL_PARTS = new AnnotationValueCache<>(value -> new UrlParts(
        value.stringValue("protocol").orElse(""), value.stringValue("host").orElse(""), value.intValue("port").orElse(-1)));

    private ConstraintMembers() {
    }

    static Size size(AnnotationValue<?> value) {
        return SIZES.get(value);
    }

    static Digits digits(AnnotationValue<?> value) {
        return DIGITS.get(value);
    }

    static DecimalLimit decimalLimit(AnnotationValue<?> value) {
        return DECIMAL_LIMITS.get(value);
    }

    static LongBound longBound(AnnotationValue<?> value) {
        return LONG_BOUNDS.get(value);
    }

    static UrlParts urlParts(AnnotationValue<?> value) {
        return URL_PARTS.get(value);
    }

    /**
     * @param min The minimum size
     * @param max The maximum size
     */
    record Size(int min, int max) {
    }

    /**
     * @param integer The digits of the integer part allowed
     * @param fraction The digits of the fraction allowed
     */
    record Digits(int integer, int fraction) {
    }

    /**
     * @param bound The bound
     * @param inclusive Whether the bound itself is allowed
     */
    record DecimalLimit(BigDecimal bound, boolean inclusive) {
    }

    /**
     * The bound of a {@code Min} or {@code Max} constraint, in each form the values are compared in.
     *
     * @param bound The bound
     * @param integer The bound as a big integer
     * @param decimal The bound as a big decimal
     */
    record LongBound(long bound, BigInteger integer, BigDecimal decimal) {
    }

    /**
     * @param protocol The protocol required, empty for any
     * @param host The host required, empty for any
     * @param port The port required, -1 for any
     */
    record UrlParts(String protocol, String host, int port) {
    }
}
