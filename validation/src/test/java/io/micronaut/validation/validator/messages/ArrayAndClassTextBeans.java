package io.micronaut.validation.validator.messages;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Pattern;

/**
 * Beans whose constraints carry array attributes and texts starting like a class name.
 */
public final class ArrayAndClassTextBeans {

    private ArrayAndClassTextBeans() {
    }

    @Introspected
    public static class Flagged {
        @Pattern(regexp = "a", flags = {Pattern.Flag.CASE_INSENSITIVE, Pattern.Flag.MULTILINE}, message = "flags {flags}")
        public String value = "x";

        public String getValue() {
            return value;
        }
    }

    @Introspected
    public static class TwoPatterns {
        @Pattern(regexp = "class \\d+")
        @Pattern(regexp = "\\d+")
        public String value = "x";

        public String getValue() {
            return value;
        }
    }
}
