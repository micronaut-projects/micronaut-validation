package io.micronaut.validation.validator.constraints.disable;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotNullMessageFromAnnotationValidator.class)
@interface NotNullMessageFromAnnotation {
    String message() default "FromAnnotation";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

