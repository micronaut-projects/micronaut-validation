package io.micronaut.validation.validator.introspection;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;

@SupportedValidationTarget(ValidationTarget.PARAMETERS)
public class FlagValidator implements ConstraintValidator<Flag, Object[]> {

    @Override
    public boolean isValid(Object[] value, ConstraintValidatorContext context) {
        return false;
    }
}
