package io.micronaut.validation.validator.map;

import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.Valid;
import java.util.Map;

@Introspected
record Author(
    String name,
    @ReflectiveAccess
    Map<String, @Valid Book> books
) {}
