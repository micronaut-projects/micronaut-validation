package io.micronaut.docs.validation.customann;

import io.micronaut.context.StaticMessageSource;
import jakarta.inject.Singleton;
@Singleton
public class EmailMessages extends StaticMessageSource {
    public static final String ANY_RECIPIENT_MESSAGE = "You have to specify to, cc or a bcc recipient";

    private static final String MESSAGE_SUFFIX = ".message";

    public EmailMessages() {
        addMessage(AnyRecipient.class.getName() + MESSAGE_SUFFIX, ANY_RECIPIENT_MESSAGE);
    }
}
