package io.micronaut.docs.validation.retry.validation;

import io.micronaut.context.annotation.Factory;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import jakarta.inject.Singleton;

@Factory
@Introspected
public class ValidatorFactory {

    @Singleton
    ConstraintValidator<ValidatedByFactory, CharSequence> validatedByFactoryValidator() {
        return (ignored1, ignored2, ignored3) -> true;
    }

}
