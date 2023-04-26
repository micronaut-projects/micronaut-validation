package io.micronaut.docs.validation.customann;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

@AnyRecipient
@Introspected
public final class Email implements Recipients {
    @Nullable
    private final String cc;

    @Nullable
    private final String to;

    public Email(@Nullable String cc, @Nullable String to) {
        this.cc = cc;
        this.to = to;
    }

    @Override
    @Nullable
    public String getCc() {
        return cc;
    }

    @Override
    @Nullable
    public String getTo() {
        return to;
    }
}
