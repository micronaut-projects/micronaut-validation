package io.micronaut.validation.composition;

import io.micronaut.context.annotation.Executable;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotNull;

/**
 * A method overloading, not overriding, a super method of an assignable parameter type.
 */
public final class OverloadHierarchy {

    private OverloadHierarchy() {
    }

    @Introspected
    public interface Base {
        @Executable
        void save(@NotNull Object item);
    }

    @Introspected
    public static class Impl implements Base {
        @Override
        @Executable
        public void save(Object item) {
        }

        @Executable
        public void save(String item) {
        }
    }

    @jakarta.inject.Singleton
    public static class Service implements Base {
        @Override
        public void save(Object item) {
        }

        public void save(String item) {
        }
    }
}
