package io.micronaut.validation.validator.reload;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

@Introspected
public class ReloadEvenValidator implements ConstraintValidator<ReloadEven, Integer> {
    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
        return value == null || value % 2 == 0;
    }
}
