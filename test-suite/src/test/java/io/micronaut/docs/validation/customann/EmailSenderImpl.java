package io.micronaut.docs.validation.customann;

import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@Singleton
public class EmailSenderImpl implements EmailSender {
    @Override
    public void send(@NonNull @NotNull @Valid Email email) {

    }
}
