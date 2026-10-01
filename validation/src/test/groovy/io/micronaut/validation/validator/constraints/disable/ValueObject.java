package io.micronaut.validation.validator.constraints.disable;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotNull;

@Introspected
record ValueObject(@NotNullMessageFromValidator @NotNull String car,
                   @NotNullMessageFromAnnotation @NotNull String bike,
                   @NotNullMessageFromValidator @NotNull String truck) {
}
