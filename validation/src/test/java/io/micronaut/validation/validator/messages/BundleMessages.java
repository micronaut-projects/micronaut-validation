package io.micronaut.validation.validator.messages;

import io.micronaut.context.MessageSource;
import io.micronaut.context.StaticMessageSource;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;


/**
 * Messages of a bundle referring to parameters and to other messages.
 */
public final class BundleMessages {

    private BundleMessages() {
    }

    @Introspected
    public static class Person {
        @NotBlank(message = "{name.required}")
        public String name = "";

        @Size(min = 2, message = "{nick.size}")
        public String nick = "a";

        @NotNull(message = "{nested}")
        public String other;

        public String getName() {
            return name;
        }

        public String getNick() {
            return nick;
        }

        public String getOther() {
            return other;
        }
    }

    @Factory
    @Requires(property = "spec.name", value = "BundleMessagesSpec")
    public static class Messages {
        @Singleton
        MessageSource bundle() {
            StaticMessageSource source = new StaticMessageSource();
            source.addMessage("name.required", "Name can't be blank");
            source.addMessage("nick.size", "at least {min}, not {unknown}");
            source.addMessage("nested", "see {name.required}");
            return source;
        }
    }
}
