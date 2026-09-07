package io.micronaut.validation.composition;

import io.micronaut.context.annotation.Executable;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The same hierarchies as beans, every type introspected and every method executable, the way the TCK archive
 * types are: the method validated is the one of the bean definition.
 */
public final class ReturnHierarchyBeans {

    private ReturnHierarchyBeans() {
    }

    @Introspected
    public interface Placer {
        @NotBlank
        @Executable
        String place();
    }

    @Introspected
    @Prototype
    public static class FromInterface implements Placer {
        @Override
        @Size(min = 10)
        @Executable
        public String place() {
            return "";
        }
    }

    @Introspected
    @Prototype
    public static class BasePlacer {
        @NotBlank
        @Executable
        public String place() {
            return "";
        }
    }

    @Introspected
    @Prototype
    public static class FromSuperClass extends BasePlacer {
        @Override
        @Size(min = 10)
        @Executable
        public String place() {
            return "";
        }
    }
}
