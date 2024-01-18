package io.micronaut.validation.validator.constraints.disable;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;

@Introspected
class NotNullMessageFromValidatorValidator implements ConstraintValidator<NotNullMessageFromValidator, String> {
    @Override
    public boolean isValid(String value,
                           @NonNull AnnotationValue<NotNullMessageFromValidator> annotationMetadata,
                           @NonNull ConstraintValidatorContext context) {
        if (value == null) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("FromValidator").addConstraintViolation();
            return false;
        }
        return true;
    }
}
