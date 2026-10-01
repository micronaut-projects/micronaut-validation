package io.micronaut.docs.validation.retry;

import io.micronaut.core.annotation.ReflectiveAccess;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.docs.validation.retry.validation.ValidInternalId;
import io.micronaut.docs.validation.retry.validation.ValidatedByFactory;

@Introspected
public record ValidatedBean(
    @ReflectiveAccess @ValidatedByFactory
    String value,
    @ReflectiveAccess @ValidInternalId
    int internalId
) {
}
