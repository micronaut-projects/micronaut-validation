package io.micronaut.validation.validator.reload;

import io.micronaut.context.annotation.Requires;
import io.micronaut.validation.validator.Validator;
import jakarta.inject.Singleton;

/**
 * An application bean that received the validator.
 */
@Singleton
@Requires(property = "validation.reload.holder", value = "true")
public class ReloadValidatorHolder {
    public final Validator validator;

    public ReloadValidatorHolder(Validator validator) {
        this.validator = validator;
    }
}
