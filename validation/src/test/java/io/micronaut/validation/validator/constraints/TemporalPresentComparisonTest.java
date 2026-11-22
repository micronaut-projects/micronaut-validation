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

import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.validator.DefaultValidatorFactory;
import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A value of a zone or an offset is compared with the present by its natural order, as the reference implementation
 * compares it: the same instant with a later local time than the present, 13:00 in Paris when it is 12:00 UTC, is in
 * the future.
 */
class TemporalPresentComparisonTest {

    private static final ClockProvider NOON_UTC = () -> Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void theSameInstantWithALaterLocalTimeIsInTheFuture() {
        try (var factory = new DefaultValidatorFactory()) {
            var validator = factory.usingContext().clockProvider(NOON_UTC).getValidator();
            Set<String> violated = new TreeSet<>();
            for (ConstraintViolation<?> violation : validator.validate(new Later())) {
                violated.add(violation.getPropertyPath().toString());
            }
            assertEquals(Set.of("pastZoned", "pastOrPresentZoned", "pastOffset", "pastOrPresentOffset",
                "pastOffsetTime", "pastOrPresentOffsetTime"), violated);
        }
    }

    @Test
    void theSameInstantWithAnEarlierLocalTimeIsInThePast() {
        try (var factory = new DefaultValidatorFactory()) {
            var validator = factory.usingContext().clockProvider(NOON_UTC).getValidator();
            Set<String> violated = new TreeSet<>();
            for (ConstraintViolation<?> violation : validator.validate(new Earlier())) {
                violated.add(violation.getPropertyPath().toString());
            }
            assertEquals(Set.of("futureZoned", "futureOrPresentZoned", "futureOffset", "futureOrPresentOffset",
                "futureOffsetTime", "futureOrPresentOffsetTime"), violated);
        }
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static class Later {
        private static final ZonedDateTime ZONED = ZonedDateTime.of(2026, 1, 15, 13, 0, 0, 0, ZoneId.of("Europe/Paris"));
        private static final OffsetDateTime OFFSET = OffsetDateTime.of(2026, 1, 15, 13, 0, 0, 0, ZoneOffset.ofHours(1));
        private static final OffsetTime OFFSET_TIME = OffsetTime.of(13, 0, 0, 0, ZoneOffset.ofHours(1));

        @Future public ZonedDateTime futureZoned = ZONED;
        @FutureOrPresent public ZonedDateTime futureOrPresentZoned = ZONED;
        @Past public ZonedDateTime pastZoned = ZONED;
        @PastOrPresent public ZonedDateTime pastOrPresentZoned = ZONED;
        @Future public OffsetDateTime futureOffset = OFFSET;
        @FutureOrPresent public OffsetDateTime futureOrPresentOffset = OFFSET;
        @Past public OffsetDateTime pastOffset = OFFSET;
        @PastOrPresent public OffsetDateTime pastOrPresentOffset = OFFSET;
        @Future public OffsetTime futureOffsetTime = OFFSET_TIME;
        @FutureOrPresent public OffsetTime futureOrPresentOffsetTime = OFFSET_TIME;
        @Past public OffsetTime pastOffsetTime = OFFSET_TIME;
        @PastOrPresent public OffsetTime pastOrPresentOffsetTime = OFFSET_TIME;
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static class Earlier {
        private static final ZonedDateTime ZONED = ZonedDateTime.of(2026, 1, 15, 7, 0, 0, 0, ZoneId.of("America/New_York"));
        private static final OffsetDateTime OFFSET = OffsetDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneOffset.ofHours(-2));
        private static final OffsetTime OFFSET_TIME = OffsetTime.of(10, 0, 0, 0, ZoneOffset.ofHours(-2));

        @Future public ZonedDateTime futureZoned = ZONED;
        @FutureOrPresent public ZonedDateTime futureOrPresentZoned = ZONED;
        @Past public ZonedDateTime pastZoned = ZONED;
        @PastOrPresent public ZonedDateTime pastOrPresentZoned = ZONED;
        @Future public OffsetDateTime futureOffset = OFFSET;
        @FutureOrPresent public OffsetDateTime futureOrPresentOffset = OFFSET;
        @Past public OffsetDateTime pastOffset = OFFSET;
        @PastOrPresent public OffsetDateTime pastOrPresentOffset = OFFSET;
        @Future public OffsetTime futureOffsetTime = OFFSET_TIME;
        @FutureOrPresent public OffsetTime futureOrPresentOffsetTime = OFFSET_TIME;
        @Past public OffsetTime pastOffsetTime = OFFSET_TIME;
        @PastOrPresent public OffsetTime pastOrPresentOffsetTime = OFFSET_TIME;
    }
}
