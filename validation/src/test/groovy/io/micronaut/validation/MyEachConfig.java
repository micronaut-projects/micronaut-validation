package io.micronaut.validation;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Requires;

import jakarta.validation.constraints.NotBlank;

@EachProperty(value = "my.config", primary = "default")
@Requires(property = "my.config")
public interface MyEachConfig {
    @NotBlank
    String getName();
}
