package io.micronaut.validation.validator.reload;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ReloadEvenValidator.class)
public @interface ReloadEven {
    String message() default "must be even";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
