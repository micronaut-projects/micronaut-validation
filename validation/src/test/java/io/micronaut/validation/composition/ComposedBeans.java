package io.micronaut.validation.composition;

import io.micronaut.core.annotation.Introspected;

/**
 * Beans carrying the composed constraints whose declarations the specification rejects. The declaration is only
 * read when the constraint is described, so each is on a bean of its own.
 */
public final class ComposedBeans {

    private ComposedBeans() {
    }

    @Introspected
    public static class WrongOverrideType {
        @Abc
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    @Introspected
    public static class OverridesMissingMember {
        @MissingMember
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    @Introspected
    public static class OverridesAbsentOccurrence {
        @BadIndex
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
