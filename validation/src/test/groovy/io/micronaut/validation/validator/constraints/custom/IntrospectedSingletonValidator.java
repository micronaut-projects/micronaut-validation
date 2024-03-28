package io.micronaut.validation.validator.constraints.custom;

import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

@Singleton
@Introspected
public class IntrospectedSingletonValidator implements ConstraintValidator<MisconfiguredConstraint, Object> {

    private final CustomValidatorDependency dependency;

    public IntrospectedSingletonValidator(CustomValidatorDependency dependency) {
        this.dependency = dependency;
    }

    @Override
    public boolean isValid(Object o, ConstraintValidatorContext constraintValidatorContext) {
        return false;
    }
}
