package io.micronaut.validation.validator;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospectionReference;
import io.micronaut.core.beans.BeanIntrospector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type without a generated introspection is a miss decided from the registered references, so the delegate,
 * and the fallbacks it would ask, are never asked for it; a generated type is looked up through the delegate.
 */
public class GeneratedBeanIntrospectorTest {

    @Introspected
    public record Generated(String name) {
    }

    public static class NotGenerated {
    }

    @Test
    void aMissDoesNotReachTheDelegate() {
        var delegate = new CountingIntrospector();
        BeanIntrospector introspector = GeneratedBeanIntrospector.of(delegate);

        assertTrue(introspector.findIntrospection(NotGenerated.class).isEmpty());
        assertTrue(introspector.findIntrospection(String.class).isEmpty());
        assertEquals(List.of(), delegate.asked);
    }

    @Test
    void aGeneratedTypeIsLookedUpThroughTheDelegate() {
        var delegate = new CountingIntrospector();
        BeanIntrospector introspector = GeneratedBeanIntrospector.of(delegate);

        BeanIntrospection<Generated> found = introspector.findIntrospection(Generated.class).orElseThrow();
        assertSame(BeanIntrospector.SHARED.getIntrospection(Generated.class), found);
        introspector.findIntrospection(Generated.class);
        introspector.findIntrospection(NotGenerated.class);

        assertEquals(List.of(Generated.class, Generated.class), delegate.asked);
        assertEquals(1, delegate.scans.get(), "the references are read once");
    }

    /**
     * The shared introspector, recording what it is asked for by class.
     */
    private static final class CountingIntrospector implements BeanIntrospector {

        final List<Class<?>> asked = new ArrayList<>();
        final AtomicInteger scans = new AtomicInteger();

        @Override
        public Collection<BeanIntrospection<Object>> findIntrospections(Predicate<? super BeanIntrospectionReference<?>> filter) {
            scans.incrementAndGet();
            return BeanIntrospector.SHARED.findIntrospections(filter);
        }

        @Override
        public Collection<Class<?>> findIntrospectedTypes(Predicate<? super BeanIntrospectionReference<?>> filter) {
            return BeanIntrospector.SHARED.findIntrospectedTypes(filter);
        }

        @Override
        public <T> Optional<BeanIntrospection<T>> findIntrospection(Class<T> beanType) {
            asked.add(beanType);
            return BeanIntrospector.SHARED.findIntrospection(beanType);
        }
    }
}
