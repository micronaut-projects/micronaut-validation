package io.micronaut.validation.composition;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A return value constrained at more than one level of a hierarchy. The specification has the constraints of
 * every level add up on the overriding method: the one an interface declares and the one the implementation
 * declares are both validated.
 */
public final class ReturnHierarchy {

    private ReturnHierarchy() {
    }

    public interface Placer {
        @NotBlank
        String place();
    }

    @Introspected
    public static class FromInterface implements Placer {
        @Override
        @Size(min = 10)
        public String place() {
            return "";
        }
    }

    public abstract static class AbstractPlacer {
        @NotBlank
        public abstract String place();
    }

    @Introspected
    public static class FromSuperClass extends AbstractPlacer {
        @Override
        @Size(min = 10)
        public String place() {
            return "";
        }
    }
}
