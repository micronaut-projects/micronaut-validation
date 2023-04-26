package io.micronaut.docs.validation.customann;

import io.micronaut.context.annotation.Factory;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import jakarta.inject.Singleton;

@Factory
public class AnyRecipientConstraintValidatorFactory {
    @Singleton
    public ConstraintValidator<AnyRecipient, Email> anyRecipientEmailConstraintValidator() {
        return (value, annotationMetadata, context) -> {
            if (value == null) {
                return true;
            }
            return RecipientsUtils.isValid(value);
        };
    }
}

