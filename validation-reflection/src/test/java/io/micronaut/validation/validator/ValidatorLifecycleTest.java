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
package io.micronaut.validation.validator;


import io.micronaut.core.annotation.Introspected;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.Payload;
import jakarta.validation.ValidationException;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorLifecycleTest {
    @Test
    void eachConstraintOwnsAnInitializedInstanceAndFactoryReleasesItOnce() throws Exception {
        CountingFactory owner = new CountingFactory();
        DefaultValidatorConfiguration configuration = new DefaultValidatorConfiguration();
        configuration.constraintValidatorFactory(owner);
        DefaultValidatorFactory factory = new DefaultValidatorFactory(configuration);
        var validator = factory.getValidator();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 500)
                .mapToObj(i -> executor.submit(() -> assertEquals(1, validator.validate(new Values()).size()))).toList();
            for (var task : tasks) {
                task.get();
            }
        }
        assertEquals(2, owner.created.get());
        assertEquals(2, owner.initialized.get());
        factory.close();
        factory.close();
        assertEquals(2, owner.released.get());
        assertThrows(ValidationException.class, factory::getValidator);
        assertThrows(ValidationException.class, factory::usingContext);
        assertThrows(ValidationException.class, () -> validator.validate(new Empty()));
        assertThrows(ValidationException.class, () -> validator.getConstraintsForClass(Empty.class));
        assertThrows(ValidationException.class, validator::forExecutables);
    }

    @Test
    void contextValidatorsAreOwnedByTheFactoryAndUseIndependentConfiguration() {
        CountingFactory first = new CountingFactory();
        CountingFactory second = new CountingFactory();
        DefaultValidatorFactory factory = new DefaultValidatorFactory();
        var context = factory.usingContext().constraintValidatorFactory(first);
        var validator1 = context.getValidator();
        context.constraintValidatorFactory(second);
        var validator2 = context.getValidator();
        assertEquals(1, validator1.validate(new Values()).size());
        assertEquals(1, validator2.validate(new Values()).size());
        factory.close();
        assertEquals(2, first.initialized.get());
        assertEquals(2, second.initialized.get());
        assertEquals(2, first.released.get());
        assertEquals(2, second.released.get());
        assertThrows(ValidationException.class, context::getValidator);
    }

    @Test
    void aFactoryMustNotReinitializeOneInstanceForDifferentAttributes() {
        CountingFactory counters = new CountingFactory();
        ThresholdValidator shared = new ThresholdValidator(counters.initialized);
        DefaultValidatorConfiguration configuration = new DefaultValidatorConfiguration();
        configuration.constraintValidatorFactory(new ConstraintValidatorFactory() {
            @Override public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> type) { return type.cast(shared); }
            @Override public void releaseInstance(ConstraintValidator<?, ?> instance) { counters.released.incrementAndGet(); }
        });
        try (DefaultValidatorFactory factory = new DefaultValidatorFactory(configuration)) {
            ValidationException failure = assertThrows(ValidationException.class, () -> factory.getValidator().validate(new Values()));
            assertTrue(failure.getMessage().contains("reused an initialized instance"));
            assertEquals(1, counters.initialized.get());
        }
        assertEquals(1, counters.released.get());
    }

    @Test
    void closureWaitsForAnActiveValidatorBeforeReleasingItsInstance() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        var released = new AtomicInteger();
        DefaultValidatorConfiguration configuration = new DefaultValidatorConfiguration();
        configuration.constraintValidatorFactory(new ConstraintValidatorFactory() {
            @Override
            public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> type) {
                return type.cast(new ThresholdValidator() {
                    @Override public boolean isValid(Integer value, ConstraintValidatorContext context) {
                        entered.countDown();
                        try {
                            assertTrue(proceed.await(10, java.util.concurrent.TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new ValidationException(e);
                        }
                        assertEquals(0, released.get());
                        return true;
                    }
                });
            }
            @Override public void releaseInstance(ConstraintValidator<?, ?> instance) { released.incrementAndGet(); }
        });
        DefaultValidatorFactory factory = new DefaultValidatorFactory(configuration);
        var validator = factory.getValidator();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var validating = executor.submit(() -> validator.validate(new OneValue()));
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var closing = executor.submit(factory::close);
            assertThrows(java.util.concurrent.TimeoutException.class, () -> closing.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            assertEquals(0, released.get());
            proceed.countDown();
            validating.get(10, java.util.concurrent.TimeUnit.SECONDS);
            closing.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            proceed.countDown();
            factory.close();
        }
        assertEquals(1, released.get());
    }

    @Test
    void initializationFailureReleasesTheInstanceAndPreservesTheCauseAndCleanupFailure() {
        var initializing = new IllegalStateException("initialization failed");
        var releasing = new IllegalStateException("release failed");
        var released = new AtomicInteger();
        var configuration = new DefaultValidatorConfiguration();
        configuration.constraintValidatorFactory(new ConstraintValidatorFactory() {
            @Override public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> type) {
                return type.cast(new ThresholdValidator() {
                    @Override public void initialize(Threshold annotation) { throw initializing; }
                });
            }
            @Override public void releaseInstance(ConstraintValidator<?, ?> instance) {
                released.incrementAndGet();
                throw releasing;
            }
        });
        try (var factory = new DefaultValidatorFactory(configuration)) {
            var failure = assertThrows(ValidationException.class, () -> factory.getValidator().validate(new OneValue()));
            assertSame(initializing, failure.getCause());
            assertArrayEquals(new Throwable[]{releasing}, initializing.getSuppressed());
        }
        assertEquals(1, released.get());
    }

    @Test
    void releaseFailuresDoNotPreventOtherInstancesFromBeingReleasedOrAllowDoubleRelease() {
        var releaseCounter = new AtomicInteger();
        var configuration = new DefaultValidatorConfiguration();
        configuration.constraintValidatorFactory(new CountingFactory() {
            @Override public void releaseInstance(ConstraintValidator<?, ?> instance) {
                throw new IllegalStateException("release " + releaseCounter.incrementAndGet());
            }
        });
        var factory = new DefaultValidatorFactory(configuration);
        assertEquals(1, factory.getValidator().validate(new Values()).size());
        var failure = assertThrows(IllegalStateException.class, factory::close);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(2, releaseCounter.get());
        factory.close();
        assertEquals(2, releaseCounter.get());
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static class OneValue {
        @Threshold(1) public int value = 5;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @Constraint(validatedBy = ThresholdValidator.class)
    public @interface Threshold {
        int value();
        String message() default "below threshold";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    static class Values {
        @Threshold(1) public int low = 5;
        @Threshold(9) public int high = 5;
    }

    @Introspected
    public static class ThresholdValidator implements ConstraintValidator<Threshold, Integer> {
        private int threshold;
        private final AtomicInteger initialized;
        public ThresholdValidator() { this(new AtomicInteger()); }
        ThresholdValidator(AtomicInteger initialized) { this.initialized = initialized; }
        @Override public void initialize(Threshold annotation) {
            threshold = annotation.value();
            initialized.incrementAndGet();
        }
        @Override public boolean isValid(Integer value, ConstraintValidatorContext context) {
            return value == null || value >= threshold;
        }
    }

    static class CountingFactory implements ConstraintValidatorFactory {
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger initialized = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();
        @Override public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> type) {
            created.incrementAndGet();
            return type.cast(new ThresholdValidator(initialized));
        }
        @Override public void releaseInstance(ConstraintValidator<?, ?> instance) { released.incrementAndGet(); }
    }
    @Introspected static class Empty { }

}
