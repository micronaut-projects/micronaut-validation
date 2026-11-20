package io.micronaut.validation.composition;

import io.micronaut.context.annotation.Executable;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotNull;

/**
 * A method implementing a generic declaration: the super type declares it on a type variable.
 */
public final class GenericHierarchy {

    private GenericHierarchy() {
    }

    @Introspected
    public interface Repo<T> {
        @Executable
        void save(@NotNull T item);
    }

    @Introspected
    public static class StringRepo implements Repo<String> {
        @Override
        @Executable
        public void save(String item) {
        }
    }

    @Introspected
    public abstract static class AbstractRepo<T> {
        @Executable
        public abstract void save(@NotNull T item);
    }

    @Introspected
    public static class StringRepoClass extends AbstractRepo<String> {
        @Override
        @Executable
        public void save(String item) {
        }
    }
}
