package io.micronaut.docs.validation.path.validations;

import io.micronaut.docs.validation.path.validations.validator.FlowValidator;
import jakarta.validation.Constraint;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = FlowValidator.class)
public @interface FlowValidation {
    String message() default "invalid Flow";
}
