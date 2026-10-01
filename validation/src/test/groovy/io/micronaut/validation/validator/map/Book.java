package io.micronaut.validation.validator.map;

import io.micronaut.core.annotation.ReflectiveAccess;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;

@Introspected
record Book(
    @ReflectiveAccess @NotBlank String title
){}
