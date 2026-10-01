package io.micronaut.docs.validation.path.model;

import io.micronaut.core.annotation.ReflectiveAccess;

import jakarta.validation.constraints.NotNull;

public record Label(@ReflectiveAccess @NotNull String key, @NotNull String value) {}
