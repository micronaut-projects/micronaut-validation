package io.micronaut.docs.validation.path.validations;

import io.micronaut.docs.validation.path.validations.validator.DateTimeValidator;
import jakarta.validation.Constraint;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DateTimeValidator.class)
public @interface DateFormat {
    String message() default "invalid date format value";
}
