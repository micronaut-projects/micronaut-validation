package io.micronaut.validation.validator.reload;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A constraint with no validator of its own: the validator is a bean, registered by the test.
 */
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
public @interface ReloadOdd {
    String message() default "must be odd";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
