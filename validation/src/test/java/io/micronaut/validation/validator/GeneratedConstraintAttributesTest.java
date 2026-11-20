package io.micronaut.validation.validator;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.annotation.URL;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ValidationException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
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

    @Test
    void urlFlagsUseTheKnownJakartaEnumContractWithoutReflection() {
        try (var factory = new DefaultValidatorFactory()) {
            var descriptor = factory.getValidator().getConstraintsForClass(UrlBean.class)
                .getConstraintsForProperty("value").getConstraintDescriptors().iterator().next();
            assertArrayEquals(new Pattern.Flag[]{Pattern.Flag.CASE_INSENSITIVE},
                assertInstanceOf(Pattern.Flag[].class, descriptor.getAttributes().get("flags")));
        }
    }

    @Introspected
    record UrlBean(@URL(flags = Pattern.Flag.CASE_INSENSITIVE) String value) { }

    @Test
    void customEnumsRequireConcreteAttributeSupportInsteadOfReturningStrings() {
        try (var factory = new DefaultValidatorFactory()) {
            var descriptor = factory.getValidator().getConstraintsForClass(ColoredBean.class)
                .getConstraintsForProperty("value").getConstraintDescriptors().iterator().next();
            var failure = assertThrows(ValidationException.class, descriptor::getAttributes);
            assertTrue(failure.getMessage().contains("color"));
            assertTrue(failure.getMessage().contains("micronaut-validation-reflection"));
        }
    }

    @Test
    void nestedAnnotationsRequireConcreteAttributeSupportInsteadOfReturningMetadata() {
        try (var factory = new DefaultValidatorFactory()) {
            var descriptor = factory.getValidator().getConstraintsForClass(TaggedBean.class)
                .getConstraintsForProperty("value").getConstraintDescriptors().iterator().next();
            var failure = assertThrows(ValidationException.class, descriptor::getAttributes);
            assertTrue(failure.getMessage().contains("tag"));
            assertTrue(failure.getMessage().contains("micronaut-validation-reflection"));
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Tag {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
    @Constraint(validatedBy = {})
    @NotNull
    @interface Tagged {
        String message() default "tagged";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
        Tag tag() default @Tag("generated");
    }

    @Introspected
    record TaggedBean(@Tagged String value) { }

    @Test
    void customMembersUsingPatternFlagsRetainTheirConcreteTypeWithoutReflection() {
        try (var factory = new DefaultValidatorFactory()) {
            var descriptor = factory.getValidator().getConstraintsForClass(CustomFlagBean.class)
                .getConstraintsForProperty("value").getConstraintDescriptors().iterator().next();
            assertSame(Pattern.Flag.CASE_INSENSITIVE, descriptor.getAttributes().get("flag"));
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
    @Constraint(validatedBy = {})
    @NotNull
    @interface CustomFlag {
        String message() default "flagged";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
        Pattern.Flag flag() default Pattern.Flag.CASE_INSENSITIVE;
    }

    @Introspected
    record CustomFlagBean(@CustomFlag String value) { }

    enum Color { RED }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
    @Constraint(validatedBy = {})
    @NotNull
    @interface Colored {
        String message() default "colored";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
        Color color() default Color.RED;
    }

    @Introspected
    record ColoredBean(@Colored String value) { }

    @Introspected
    public record Flagged(@Pattern(regexp = "a", flags = {Pattern.Flag.CASE_INSENSITIVE, Pattern.Flag.MULTILINE}) String value) { }
}
