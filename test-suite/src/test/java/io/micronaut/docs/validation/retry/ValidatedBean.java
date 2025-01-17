package io.micronaut.docs.validation.retry;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.docs.validation.retry.validation.ValidInternalId;
import io.micronaut.docs.validation.retry.validation.ValidatedByFactory;

@Introspected
public record ValidatedBean(
    @ValidatedByFactory
    String value,
    @ValidInternalId
    int internalId
) {
}
