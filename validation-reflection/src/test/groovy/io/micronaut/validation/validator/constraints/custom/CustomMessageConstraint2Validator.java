package io.micronaut.validation.validator.constraints.custom;

import jakarta.inject.Singleton;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

@Singleton
public class CustomMessageConstraint2Validator implements ConstraintValidator<CustomMessageConstraint, Object> {

    private final CustomValidatorDependency otherBean;

    public CustomMessageConstraint2Validator(CustomValidatorDependency otherBean) {
        this.otherBean = otherBean;
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        otherBean.doSomeStuff();
        context.buildConstraintViolationWithTemplate("custom invalid").addConstraintViolation();
        context.disableDefaultConstraintViolation();
        return false;
    }
}
