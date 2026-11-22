package io.micronaut.validation.validator.compat;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.validator.DefaultValidatorFactory;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Payload;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A constraint of the application gets an annotation builder from the processor (micronaut-core#14055): its Jakarta
 * validator is initialized with an annotation instance and its descriptor answers typed attributes, without the
 * reflection module.
 */
public class GeneratedAnnotationBuilderTest {

    public enum Level { LOW, HIGH }

    @Constraint(validatedBy = AtLeast.Validator.class)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface AtLeast {
        Level value();

        String message() default "below {value}";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};

        class Validator implements ConstraintValidator<AtLeast, Level> {
            private Level minimum;

            @Override
            public void initialize(AtLeast constraintAnnotation) {
                minimum = constraintAnnotation.value();
            }

            @Override
            public boolean isValid(Level value, ConstraintValidatorContext context) {
                return value == null || value.compareTo(minimum) >= 0;
            }
        }
    }

    @Introspected
    public record Graded(@AtLeast(Level.HIGH) Level level) { }

    @Test
    void aJakartaValidatorIsInitializedAndAttributesAreTyped() {
        try (var factory = new DefaultValidatorFactory()) {
            Set<ConstraintViolation<Graded>> violations = factory.getValidator().validate(new Graded(Level.LOW));
            assertEquals(1, violations.size(), violations.toString());
            ConstraintViolation<Graded> violation = violations.iterator().next();
            assertEquals("below HIGH", violation.getMessage());
            assertSame(Level.HIGH, violation.getConstraintDescriptor().getAttributes().get("value"));
            assertSame(Level.HIGH, ((AtLeast) violation.getConstraintDescriptor().getAnnotation()).value());
        }
    }
}
