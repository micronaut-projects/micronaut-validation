package io.micronaut.validation.validator;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.context.annotation.Requires;
import io.micronaut.validation.annotation.InList;
import io.micronaut.validation.validator.constraints.DefaultInternalConstraintValidatorFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InternalConstraintValidatorOwnershipTest {
    @Test
    void noArgumentBeansReceiveMemberInjectionAndLifecycleCallbacks() {
        try (var context = ApplicationContext.run(Map.of("spec.name", "InternalConstraintValidatorOwnershipTest"))) {
            var factory = new DefaultInternalConstraintValidatorFactory(context);
            var validator = factory.getInstance(MemberInjectedValidator.class);
            assertNotNull(validator);
            assertNotNull(validator.counters);
            assertTrue(validator.started);
            factory.releaseInstance(validator);
            assertTrue(validator.stopped);
        }
    }

    @Singleton
    @Requires(property = "spec.name", value = "InternalConstraintValidatorOwnershipTest")
    public static class MemberInjectedValidator implements ConstraintValidator<InList, Integer> {
        @Inject Counters counters;
        boolean started;
        boolean stopped;
        public MemberInjectedValidator() { }
        @PostConstruct void start() { started = true; }
        @PreDestroy void stop() { stopped = true; }
        @Override public boolean isValid(Integer value, ConstraintValidatorContext context) { return true; }
    }

    @Test
    void ownedSingletonValidatorInstancesAreDistinctAndDoNotReleaseTheApplicationSingleton() {
        Counters counters;
        try (var context = ApplicationContext.run(Map.of("spec.name", "InternalConstraintValidatorOwnershipTest"))) {
            counters = context.getBean(Counters.class);
            var application = context.getBean(SingletonValidator.class);
            var factory = new DefaultInternalConstraintValidatorFactory(context);
            var first = factory.getInstance(SingletonValidator.class);
            var second = factory.getInstance(SingletonValidator.class);
            assertNotNull(first);
            assertNotNull(second);
            assertNotSame(application, first);
            assertNotSame(first, second);
            assertEquals(3, counters.created.get());
            factory.releaseInstance(first);
            factory.releaseInstance(first);
            factory.releaseInstance(second);
            assertEquals(2, counters.destroyed.get());
            assertEquals(2, counters.dependenciesDestroyed.get());
            assertSame(application, context.getBean(SingletonValidator.class));
        }
        assertEquals(3, counters.destroyed.get());
        assertEquals(3, counters.dependenciesDestroyed.get());
    }

    @Test
    void prototypeRegistrationsAreDestroyedExactlyOnceByTheirOwner() {
        Counters counters;
        try (var context = ApplicationContext.run(Map.of("spec.name", "InternalConstraintValidatorOwnershipTest"))) {
            counters = context.getBean(Counters.class);
            var factory = new DefaultInternalConstraintValidatorFactory(context);
            var first = factory.getInstance(PrototypeValidator.class);
            var second = factory.getInstance(PrototypeValidator.class);
            assertNotNull(first);
            assertNotNull(second);
            assertNotSame(first, second);
            assertEquals(2, counters.created.get());
            factory.releaseInstance(first);
            factory.releaseInstance(first);
            factory.releaseInstance(second);
            assertEquals(2, counters.destroyed.get());
            assertEquals(2, counters.dependenciesDestroyed.get());
        }
        assertEquals(2, counters.destroyed.get());
            assertEquals(2, counters.dependenciesDestroyed.get());
    }

    @Singleton
    @Requires(property = "spec.name", value = "InternalConstraintValidatorOwnershipTest")
    static class Counters {
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger destroyed = new AtomicInteger();
        final AtomicInteger dependenciesDestroyed = new AtomicInteger();
    }

    @Singleton
    @Requires(property = "spec.name", value = "InternalConstraintValidatorOwnershipTest")
    static class SingletonValidator extends OwnedValidator {
        SingletonValidator(Counters counters, OwnedDependency dependency) { super(counters); }
    }

    @Prototype
    @Requires(property = "spec.name", value = "InternalConstraintValidatorOwnershipTest")
    static class PrototypeValidator extends OwnedValidator {
        PrototypeValidator(Counters counters, OwnedDependency dependency) { super(counters); }
    }

    @Prototype
    @Requires(property = "spec.name", value = "InternalConstraintValidatorOwnershipTest")
    static class OwnedDependency {
        private final Counters counters;
        OwnedDependency(Counters counters) { this.counters = counters; }
        @PreDestroy void destroy() { counters.dependenciesDestroyed.incrementAndGet(); }
    }

    abstract static class OwnedValidator implements ConstraintValidator<InList, Integer> {
        private final Counters counters;
        OwnedValidator(Counters counters) {
            this.counters = counters;
            counters.created.incrementAndGet();
        }
        @Override public boolean isValid(Integer value, ConstraintValidatorContext context) { return true; }
        @PreDestroy void destroy() { counters.destroyed.incrementAndGet(); }
    }
}
