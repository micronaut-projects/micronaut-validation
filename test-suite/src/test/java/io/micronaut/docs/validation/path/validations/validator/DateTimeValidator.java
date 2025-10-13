package io.micronaut.docs.validation.path.validations.validator;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.docs.validation.path.validations.DateFormat;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.inject.Singleton;

import java.text.SimpleDateFormat;
import java.util.Date;

@Singleton
public class DateTimeValidator implements ConstraintValidator<DateFormat, String> {
    @Override
    public boolean isValid(
        @Nullable String value,
        @NonNull AnnotationValue<DateFormat> annotationMetadata,
        @NonNull ConstraintValidatorContext context) {
        if (value == null) {
            return true; // nulls are allowed according to spec
        }

        try {
            Date now = new Date();
            SimpleDateFormat dateFormat = new SimpleDateFormat(value);
            dateFormat.format(now);
        } catch (Exception e) {
            context.messageTemplate("invalid date format value '({validatedValue})': " + e.getMessage());

            return false;
        }
        return true;
    }
}
