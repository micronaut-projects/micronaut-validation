package io.micronaut.validation.validator;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedConstraintAttributesTest {
    @Test
    void enumArraysHaveTheirDeclaredTypeAndAreDefensivelyCopied() {
        try (var factory = new DefaultValidatorFactory()) {
            var descriptor = factory.getValidator().getConstraintsForClass(Flagged.class)
                .getConstraintsForProperty("value").getConstraintDescriptors().iterator().next();
            var flags = assertInstanceOf(Pattern.Flag[].class, descriptor.getAttributes().get("flags"));
            assertArrayEquals(new Pattern.Flag[]{Pattern.Flag.CASE_INSENSITIVE, Pattern.Flag.MULTILINE}, flags);
            flags[0] = Pattern.Flag.DOTALL;
            assertArrayEquals(new Pattern.Flag[]{Pattern.Flag.CASE_INSENSITIVE, Pattern.Flag.MULTILINE},
                (Pattern.Flag[]) descriptor.getAttributes().get("flags"));
        }
    }

    @Introspected
    public record Flagged(@Pattern(regexp = "a", flags = {Pattern.Flag.CASE_INSENSITIVE, Pattern.Flag.MULTILINE}) String value) { }
}
