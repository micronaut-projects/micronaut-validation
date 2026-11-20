package io.micronaut.validation.reflection;

import io.micronaut.core.annotation.Introspected;

/**
 * Beans carrying overrides only the declared form of the composed constraint can reject.
 */
public final class OverrideBeans {

    private OverrideBeans() {
    }

    @Introspected
    public static class OverridesMissingMember {
        @MissingMember
        private String value;

        public String getValue() {
            return value;
        }
    }

    @Introspected
    public static class OverridesAbsentOccurrence {
        @BadIndex
        private String value;

        public String getValue() {
            return value;
        }
    }
}
