package io.micronaut.validation.validator.compat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.validator.Validator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPathCompatibilityTest {
    static ApplicationContext ctx;
    static Validator validator;

    @BeforeAll
    static void start() {
        ctx = ApplicationContext.run();
        validator = ctx.getBean(Validator.class);
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    // A: a type introspected through @Introspected(classes = ...), the way third-party types are introspected
    public static class Imported {
        private final String name;
        public Imported(String name) { this.name = name; }
        public @NotNull String getName() { return name; }
    }

    @Introspected(classes = Imported.class)
    static class ImportHolder { }

    @Test
    void importedIntrospectionIsValidated() {
        Set<ConstraintViolation<Imported>> violations = validator.validate(new Imported(null));
        assertEquals(1, violations.size(), violations.toString());
    }

    // B: a plain Jakarta ConstraintValidator with nothing to initialize
    @Constraint(validatedBy = AlwaysOkValidator.class)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface AlwaysOk {
        String message() default "never";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class AlwaysOkValidator implements jakarta.validation.ConstraintValidator<AlwaysOk, String> {
        @Override
        public boolean isValid(String value, jakarta.validation.ConstraintValidatorContext context) {
            return true;
        }
    }

    @Introspected
    public record WithJakartaValidator(@AlwaysOk String value) { }

    @Test
    void plainJakartaValidatorRuns() {
        assertTrue(validator.validate(new WithJakartaValidator("a")).isEmpty());
    }

    // C: a custom constraint with an enum member, Micronaut validator, message not using the member
    public enum Level { LOW, HIGH }

    @Constraint(validatedBy = {})
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Severity {
        Level level() default Level.LOW;
        String message() default "bad value";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    @jakarta.inject.Singleton
    public static class SeverityValidator implements io.micronaut.validation.validator.constraints.ConstraintValidator<Severity, String> {
        @Override
        public boolean isValid(String value, AnnotationValue<Severity> annotationMetadata, ConstraintValidatorContext context) {
            return false;
        }
    }

    @Introspected
    public record WithEnumMember(@Severity(level = Level.HIGH) String value) { }

    @Test
    void enumMemberConstraintReportsViolation() {
        Set<ConstraintViolation<WithEnumMember>> violations = validator.validate(new WithEnumMember("a"));
        assertEquals(1, violations.size(), violations.toString());
        assertEquals("bad value", violations.iterator().next().getMessage());
    }
}
