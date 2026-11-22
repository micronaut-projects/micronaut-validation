package io.micronaut.validation.validator.constraints;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.validation.constraints.Size;

/**
 * A validator described by an introspection: the processor records the constraint and the type it validates.
 */
@Introspected
public class IntrospectedLengthValidator implements ConstraintValidator<Size, CharSequence> {

    @Override
    public boolean isValid(@Nullable CharSequence value, @NonNull AnnotationValue<Size> annotationMetadata, @NonNull ConstraintValidatorContext context) {
        return value == null || value.length() >= 3;
    }
}
