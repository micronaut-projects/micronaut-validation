/*
 * Copyright 2017-2023 original authors
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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.beans.BeanWrapper;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.chrono.HijrahDate;
import java.time.chrono.JapaneseDate;
import java.time.chrono.MinguoDate;
import java.time.chrono.ThaiBuddhistDate;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Default local validators.
 * @author Denis Stepanov
 */
@Introspected(accessKind = Introspected.AccessKind.FIELD)
final class InternalConstraintValidators {

    final ConstraintValidator<AssertFalse, Boolean> assertFalseValidator =
        (value, annotationMetadata, context) -> value == null || !value;

    final ConstraintValidator<AssertTrue, Boolean> assertTrueValidator =
        (value, annotationMetadata, context) -> value == null || value;

    final DecimalMaxValidator<CharSequence> decimalMaxValidatorCharSequence =
        (value, bigDecimal) -> new BigDecimal(value.toString()).compareTo(bigDecimal);

    final DecimalMaxValidator<Number> decimalMaxValidatorNumber = InternalConstraintValidators::compareNumber;

    final DecimalMinValidator<CharSequence> decimalMinValidatorCharSequence =
        (value, bigDecimal) -> new BigDecimal(value.toString()).compareTo(bigDecimal);

    final DecimalMinValidator<Number> decimalMinValidatorNumber = InternalConstraintValidators::compareNumber;

    final DigitsValidator<Number> digitsValidatorNumber = value -> {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        return new BigDecimal(value.toString());
    };

    final DigitsValidator<CharSequence> digitsValidatorCharSequence =
        value -> new BigDecimal(value.toString());

    final ConstraintValidator<Max, Number> maxNumberValidator =
        (value, annotationMetadata, context) -> {
            if (value == null) {
                return true; // nulls are allowed according to spec
            }
            final Long max = annotationMetadata.getValue(Long.class).orElseThrow(() ->
                new ValidationException("@Max annotation specified without value")
            );

            if (value instanceof BigInteger) {
                return ((BigInteger) value).compareTo(BigInteger.valueOf(max)) <= 0;
            } else if (value instanceof BigDecimal) {
                return ((BigDecimal) value).compareTo(BigDecimal.valueOf(max)) <= 0;
            }
            return value.longValue() <= max;
        };

    final ConstraintValidator<Min, Number> minNumberValidator =
        (value, annotationMetadata, context) -> {
            if (value == null) {
                return true; // nulls are allowed according to spec
            }
            final Long max = annotationMetadata.getValue(Long.class).orElseThrow(() ->
                new ValidationException("@Min annotation specified without value")
            );

            if (value instanceof BigInteger) {
                return ((BigInteger) value).compareTo(BigInteger.valueOf(max)) >= 0;
            } else if (value instanceof BigDecimal) {
                return ((BigDecimal) value).compareTo(BigDecimal.valueOf(max)) >= 0;
            }
            return value.longValue() >= max;
        };

    final ConstraintValidator<Negative, Number> negativeNumberValidator =
        (value, annotationMetadata, context) -> {
            // null is allowed according to spec
            if (value == null) {
                return true;
            }
            if (value instanceof BigDecimal) {
                return ((BigDecimal) value).signum() < 0;
            }
            if (value instanceof BigInteger) {
                return ((BigInteger) value).signum() < 0;
            }
            if (value instanceof Double ||
                value instanceof Float ||
                value instanceof DoubleAdder ||
                value instanceof DoubleAccumulator) {
                return value.doubleValue() < 0;
            }
            return value.longValue() < 0;
        };

    final ConstraintValidator<NegativeOrZero, Number> negativeOrZeroNumberValidator =
        (value, annotationMetadata, context) -> {
            // null is allowed according to spec
            if (value == null) {
                return true;
            }
            if (value instanceof BigDecimal) {
                return ((BigDecimal) value).signum() <= 0;
            }
            if (value instanceof BigInteger) {
                return ((BigInteger) value).signum() <= 0;
            }
            if (value instanceof Double ||
                value instanceof Float ||
                value instanceof DoubleAdder ||
                value instanceof DoubleAccumulator) {
                return value.doubleValue() <= 0;
            }
            return value.longValue() <= 0;
        };

    final ConstraintValidator<Positive, Number> positiveNumberValidator =
        (value, annotationMetadata, context) -> {
            // null is allowed according to spec
            if (value == null) {
                return true;
            }
            if (value instanceof BigDecimal) {
                return ((BigDecimal) value).signum() > 0;
            }
            if (value instanceof BigInteger) {
                return ((BigInteger) value).signum() > 0;
            }
            if (value instanceof Double ||
                value instanceof Float ||
                value instanceof DoubleAdder ||
                value instanceof DoubleAccumulator) {
                return value.doubleValue() > 0;
            }
            return value.longValue() > 0;
        };

    final ConstraintValidator<PositiveOrZero, Number> positiveOrZeroNumberValidator =
        (value, annotationMetadata, context) -> {
            // null is allowed according to spec
            if (value == null) {
                return true;
            }
            if (value instanceof BigDecimal) {
                return ((BigDecimal) value).signum() >= 0;
            }
            if (value instanceof BigInteger) {
                return ((BigInteger) value).signum() >= 0;
            }
            if (value instanceof Double ||
                value instanceof Float ||
                value instanceof DoubleAdder ||
                value instanceof DoubleAccumulator) {
                return value.doubleValue() >= 0;
            }
            return value.longValue() >= 0;
        };

    final ConstraintValidator<NotBlank, CharSequence> notBlankValidator =
        (value, annotationMetadata, context) ->
            StringUtils.isNotEmpty(value) && !value.toString().isBlank();

    final ConstraintValidator<NotNull, Object> notNullValidator =
        (value, annotationMetadata, context) -> value != null;

    final ConstraintValidator<Null, Object> nullValidator =
        (value, annotationMetadata, context) -> value == null;

    final ConstraintValidator<NotEmpty, byte[]> notEmptyByteArrayValidator =
        (value, annotationMetadata, context) -> value != null && value.length > 0;

    final ConstraintValidator<NotEmpty, char[]> notEmptyCharArrayValidator =
        (value, annotationMetadata, context) -> value != null && value.length > 0;

    final ConstraintValidator<NotEmpty, boolean[]> notEmptyBooleanArrayValidator =
        (value, annotationMetadata, context) -> value != null && value.length > 0;

    final ConstraintValidator<NotEmpty, double[]> notEmptyDoubleArrayValidator =
        (value, annotationMetadata, context) -> value != null && value.length > 0;

    final ConstraintValidator<NotEmpty, float[]> notEmptyFloatArrayValidator =
        (value, annotationMetadata, context) -> value != null && value.length > 0;

    final ConstraintValidator<NotEmpty, int[]> notEmptyIntArrayValidator =
        (value, annotationMetadata, context) -> value != null && value.length > 0;

    final ConstraintValidator<NotEmpty, long[]> notEmptyLongArrayValidator =
        (value, annotationMetadata, context) -> value != null && value.length > 0;

    final ConstraintValidator<NotEmpty, Object[]> notEmptyObjectArrayValidator = (value, annotationMetadata, context) -> value != null && value.length > 0;

    final ConstraintValidator<NotEmpty, short[]> notEmptyShortArrayValidator =
        (value, annotationMetadata, context) -> value != null && value.length > 0;

    final ConstraintValidator<NotEmpty, CharSequence> notEmptyCharSequenceValidator =
        (value, annotationMetadata, context) -> StringUtils.isNotEmpty(value);

    final ConstraintValidator<NotEmpty, Collection> notEmptyCollectionValidator =
        (value, annotationMetadata, context) -> CollectionUtils.isNotEmpty(value);

    final ConstraintValidator<NotEmpty, Map> notEmptyMapValidator =
        (value, annotationMetadata, context) -> CollectionUtils.isNotEmpty(value);

    final SizeValidator<Object[]> sizeObjectArrayValidator = value -> value.length;

    final SizeValidator<byte[]> sizeByteArrayValidator = value -> value.length;

    final SizeValidator<char[]> sizeCharArrayValidator = value -> value.length;

    final SizeValidator<boolean[]> sizeBooleanArrayValidator = value -> value.length;

    final SizeValidator<double[]> sizeDoubleArrayValidator = value -> value.length;

    final SizeValidator<float[]> sizeFloatArrayValidator = value -> value.length;

    final SizeValidator<int[]> sizeIntArrayValidator = value -> value.length;

    final SizeValidator<long[]> sizeLongArrayValidator = value -> value.length;

    final SizeValidator<short[]> sizeShortArrayValidator = value -> value.length;

    final SizeValidator<CharSequence> sizeCharSequenceValidator = CharSequence::length;

    final SizeValidator<Collection> sizeCollectionValidator = Collection::size;

    final SizeValidator<Map> sizeMapValidator = Map::size;

    final ConstraintValidator<Past, TemporalAccessor> pastTemporalAccessorConstraintValidator =
        (value, annotationMetadata, context) -> {
            if (value == null) {
                // null is valid according to spec
                return true;
            }
            Comparable comparable = getNow(value, context.getClockProvider().getClock());
            return comparable.compareTo(value) > 0;
        };

    final ConstraintValidator<Past, Date> pastDateConstraintValidator =
        (value, annotationMetadata, context) -> {
            if (value == null) {
                // null is valid according to spec
                return true;
            }
            Comparable<Date> comparable = Date.from(context.getClockProvider().getClock().instant());
            return comparable.compareTo(value) > 0;
        };

    final ConstraintValidator<Past, Calendar> pastCalendarConstraintValidator =
        (value, annotationMetadata, context) -> {
            if (value == null) {
                // null is valid according to spec
                return true;
            }
            Comparable comparable = getNow(LocalDateTime.now(), context.getClockProvider().getClock());
            return comparable.compareTo(toLocalDateTime(value)) > 0;
        };

    final ConstraintValidator<PastOrPresent, TemporalAccessor> pastOrPresentTemporalAccessorConstraintValidator =
        (value, annotationMetadata, context) -> {
            if (value == null) {
                // null is valid according to spec
                return true;
            }
            Comparable comparable = getNow(value, context.getClockProvider().getClock());
            return comparable.compareTo(value) >= 0;
        };

    final ConstraintValidator<PastOrPresent, Date> pastOrPresentDateConstraintValidator =
        (value, annotationMetadata, context) -> {
            if (value == null) {
                // null is valid according to spec
                return true;
            }
            Comparable<Date> comparable = Date.from(context.getClockProvider().getClock().instant());
            return comparable.compareTo(value) >= 0;
        };

    final ConstraintValidator<PastOrPresent, Calendar> pastOrPresentCalendarConstraintValidator =
        (value, annotationMetadata, context) -> {
            if (value == null) {
                // null is valid according to spec
                return true;
            }
            Comparable comparable = getNow(LocalDateTime.now(), context.getClockProvider().getClock());
            return comparable.compareTo(toLocalDateTime(value)) >= 0;
        };

    final ConstraintValidator<Future, TemporalAccessor> futureTemporalAccessorConstraintValidator = (value, annotationMetadata, context) -> {
        if (value == null) {
            // null is valid according to spec
            return true;
        }
        Comparable comparable = getNow(value, context.getClockProvider().getClock());
        return comparable.compareTo(value) < 0;
    };

    final ConstraintValidator<Future, Calendar> futureCalendarConstraintValidator = (value, annotationMetadata, context) -> {
        if (value == null) {
            // null is valid according to spec
            return true;
        }
        Comparable comparable = getNow(LocalDateTime.now(), context.getClockProvider().getClock());
        return comparable.compareTo(toLocalDateTime(value)) < 0;
    };

    final ConstraintValidator<Future, Date> futureDateConstraintValidator = (value, annotationMetadata, context) -> {
        if (value == null) {
            // null is valid according to spec
            return true;
        }
        Comparable<Date> comparable = Date.from(context.getClockProvider().getClock().instant());
        return comparable.compareTo(value) < 0;
    };

    final ConstraintValidator<FutureOrPresent, TemporalAccessor> futureOrPresentTemporalAccessorConstraintValidator = (value, annotationMetadata, context) -> {
        if (value == null) {
            // null is valid according to spec
            return true;
        }
        Comparable comparable = getNow(value, context.getClockProvider().getClock());
        return comparable.compareTo(value) <= 0;
    };

    final ConstraintValidator<FutureOrPresent, Date> futureOrPresentDateConstraintValidator = (value, annotationMetadata, context) -> {
        if (value == null) {
            // null is valid according to spec
            return true;
        }
        Comparable<Date> comparable = Date.from(context.getClockProvider().getClock().instant());
        return comparable.compareTo(value) <= 0;
    };

    final ConstraintValidator<FutureOrPresent, Calendar> futureOrPresentCalendarConstraintValidator = (value, annotationMetadata, context) -> {
        if (value == null) {
            // null is valid according to spec
            return true;
        }
        Comparable comparable = getNow(LocalDateTime.now(), context.getClockProvider().getClock());
        return comparable.compareTo(toLocalDateTime(value)) <= 0;
    };

    /**
     * Performs the comparison for number.
     *
     * @param value      The value
     * @param bigDecimal The big decimal
     * @return The result
     */
    private static int compareNumber(@NonNull Number value, @NonNull BigDecimal bigDecimal) {
        int result;
        if (value instanceof BigDecimal) {
            result = ((BigDecimal) value).compareTo(bigDecimal);
        } else if (value instanceof BigInteger) {
            result = new BigDecimal((BigInteger) value).compareTo(bigDecimal);
        } else {
            result = BigDecimal.valueOf(value.doubleValue()).compareTo(bigDecimal);
        }
        return result;
    }

    public static LocalDateTime toLocalDateTime(Calendar calendar) {
        if (calendar == null) {
            return null;
        }
        TimeZone tz = calendar.getTimeZone();
        ZoneId zid = tz == null ? ZoneId.systemDefault() : tz.toZoneId();
        return LocalDateTime.ofInstant(calendar.toInstant(), zid);
    }

    private static Comparable<? extends TemporalAccessor> getNow(TemporalAccessor value, Clock clock) {
        if (!(value instanceof Comparable)) {
            throw new IllegalArgumentException("TemporalAccessor value must be comparable");
        }

        if (value instanceof LocalDateTime) {
            return LocalDateTime.now(clock);
        } else if (value instanceof Instant) {
            return Instant.now(clock);
        } else if (value instanceof ZonedDateTime) {
            return ZonedDateTime.now(clock);
        } else if (value instanceof OffsetDateTime) {
            return OffsetDateTime.now(clock);
        } else if (value instanceof LocalDate) {
            return LocalDate.now(clock);
        } else if (value instanceof LocalTime) {
            return LocalTime.now(clock);
        } else if (value instanceof OffsetTime) {
            return OffsetTime.now(clock);
        } else if (value instanceof MonthDay) {
            return MonthDay.now(clock);
        } else if (value instanceof Year) {
            return Year.now(clock);
        } else if (value instanceof YearMonth) {
            return YearMonth.now(clock);
        } else if (value instanceof HijrahDate) {
            return HijrahDate.now(clock);
        } else if (value instanceof JapaneseDate) {
            return JapaneseDate.now(clock);
        } else if (value instanceof ThaiBuddhistDate) {
            return ThaiBuddhistDate.now(clock);
        } else if (value instanceof MinguoDate) {
            return MinguoDate.now(clock);
        }
        throw new IllegalArgumentException("TemporalAccessor value type not supported: " + value.getClass());
    }

    public static List<Map.Entry<Argument<Object>, ConstraintValidator<?, ?>>> getConstraintValidators() {
        InternalConstraintValidators bean = new InternalConstraintValidators();
        BeanWrapper<InternalConstraintValidators> wrapper = BeanWrapper.findWrapper(InternalConstraintValidators.class, bean).orElse(null);
        if (wrapper == null) {
            throw new IllegalArgumentException("Cannot retrieve constraint validators");
        }
        return wrapper.getBeanProperties()
            .stream()
            .<Map.Entry<Argument<Object>, ConstraintValidator<?, ?>>>map(p -> Map.entry(p.asArgument(), (ConstraintValidator<?, ?>) p.get(bean)))
            .toList();
    }
}
