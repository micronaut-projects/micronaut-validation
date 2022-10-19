package io.micronaut.validation.validator.map;

import io.micronaut.core.annotation.Introspected;
import javax.validation.constraints.NotBlank;

@Introspected
record Book(
    @NotBlank String title
){}
