package io.micronaut.validation.validator.constraints.custom;

import jakarta.inject.Singleton;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

@Singleton
public class CustomMessageConstraint2Validator implements ConstraintValidator<CustomMessageConstraint, Object> {
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        context.buildConstraintViolationWithTemplate("custom invalid").addConstraintViolation();
        context.disableDefaultConstraintViolation();
        return false;
    }
}
