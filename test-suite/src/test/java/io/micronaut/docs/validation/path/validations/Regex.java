package io.micronaut.docs.validation.path.validations;

import io.micronaut.docs.validation.path.validations.validator.PatternValidator;
import jakarta.validation.Constraint;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PatternValidator.class)
public @interface Regex {
    String message() default "invalid pattern ({validatedValue})";
}
