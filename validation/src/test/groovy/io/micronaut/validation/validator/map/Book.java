package io.micronaut.validation.validator.map;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;

@Introspected
record Book(
    @NotBlank String title
){}
