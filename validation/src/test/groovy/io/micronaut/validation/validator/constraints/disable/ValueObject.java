package io.micronaut.validation.validator.constraints.disable;

import io.micronaut.core.annotation.ReflectiveAccess;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotNull;

@Introspected
record ValueObject(@ReflectiveAccess @NotNullMessageFromValidator @NotNull String car,
                   @ReflectiveAccess @NotNullMessageFromAnnotation @NotNull String bike,
                   @ReflectiveAccess @NotNullMessageFromValidator @NotNull String truck) {
}
