package io.micronaut.docs.validation.path.validations;

import io.micronaut.docs.validation.path.validations.validator.WorkerGroupValidator;
import jakarta.validation.Constraint;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = WorkerGroupValidator.class)
public @interface WorkerGroupValidation {
    String message() default "invalid workerGroup property";
}
