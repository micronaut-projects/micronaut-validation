package io.micronaut.docs.validation.path.model;

import jakarta.validation.constraints.NotNull;

public record Label(@NotNull String key, @NotNull String value) {}
