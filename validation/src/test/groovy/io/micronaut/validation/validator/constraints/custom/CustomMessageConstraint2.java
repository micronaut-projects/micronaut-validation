package io.micronaut.validation.validator.constraints.custom;

import jakarta.validation.Constraint;

import java.lang.annotation.Retention;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Retention(RUNTIME)
@Constraint(validatedBy = CustomMessageConstraint2Validator.class)
@interface CustomMessageConstraint2 {
    String message() default "invalid";
}
