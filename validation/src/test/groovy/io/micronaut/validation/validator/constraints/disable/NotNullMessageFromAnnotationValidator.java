package io.micronaut.validation.validator.constraints.disable;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;

@Introspected
class NotNullMessageFromAnnotationValidator implements ConstraintValidator<NotNullMessageFromAnnotation, String> {
    @Override
    public boolean isValid(String value,
                           @NonNull AnnotationValue<NotNullMessageFromAnnotation> annotationMetadata,
                           @NonNull ConstraintValidatorContext context) {
        return value != null;
    }
}
