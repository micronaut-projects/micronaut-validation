package io.micronaut.docs.validation.customann;

import org.jspecify.annotations.NonNull;
import jakarta.inject.Singleton;


@Singleton
public class EmailSenderImpl implements EmailSender {
    @Override
    public void send(@NonNull Email email) {

    }
}
