package io.micronaut.validation.composition;

import io.micronaut.context.annotation.Executable;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The same hierarchies with the super types introspected as well, the way the TCK archive types are.
 */
public final class ReturnHierarchyIntrospected {

    private ReturnHierarchyIntrospected() {
    }

    @Introspected
    public interface Placer {
        @NotBlank
        @Executable
        String place();
    }

    @Introspected
    public static class FromInterface implements Placer {
        @Override
        @Size(min = 10)
        @Executable
        public String place() {
            return "";
        }
    }

    @Introspected
    public abstract static class AbstractPlacer {
        @NotBlank
        @Executable
        public abstract String place();
    }

    @Introspected
    public static class FromSuperClass extends AbstractPlacer {
        @Override
        @Size(min = 10)
        @Executable
        public String place() {
            return "";
        }
    }
}
