package io.micronaut.validation.reflection;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.Payload;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * A constraint declaring no validators of its own gets them from beans, an XML constraint definition or its
 * composition: the strict checks cannot tell from its type alone which targets they support.
 */
class ExternallyValidatedDefinitionTest {

    @Constraint(validatedBy = {})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ExternallyValidated {
        String message() default "invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};

        ConstraintTarget validationAppliesTo() default ConstraintTarget.IMPLICIT;
    }

    @Test
    void aConstraintWithoutDeclaredValidatorsMayDeclareValidationAppliesTo() {
        assertDoesNotThrow(() -> new ReflectionValidationSupport().checkConstraintDefinition(ExternallyValidated.class));
    }
}
