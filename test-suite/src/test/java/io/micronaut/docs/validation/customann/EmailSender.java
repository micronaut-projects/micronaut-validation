package io.micronaut.docs.validation.customann;

import io.micronaut.core.annotation.NonNull;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public interface EmailSender {

    void send(@NonNull @NotNull @Valid Email email);
}
