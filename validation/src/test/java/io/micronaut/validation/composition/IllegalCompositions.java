package io.micronaut.validation.composition;

import io.micronaut.context.annotation.Executable;
import io.micronaut.core.annotation.Introspected;

/**
 * Beans carrying the compositions the specification rejects.
 */
public final class IllegalCompositions {

    private IllegalCompositions() {
    }

    @Introspected
    public static class WithInvalidOverride {
        @InvalidOverride
        private String zip = "foobar";

        public String getZip() {
            return zip;
        }
    }

    @Introspected
    public static class WithDirectAndContainer {
        @DirectAndContainer
        private String zip = "abc";

        public String getZip() {
            return zip;
        }
    }

    @Introspected
    public static class WithMixedTargets {
        @ParametersOnly
        @Executable
        public Object doSomething(int i) {
            return null;
        }
    }
}
