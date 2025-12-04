package io.micronaut.docs.validation.customann;

import org.jspecify.annotations.Nullable;
public interface Recipients {

    @Nullable
    String getTo();

    @Nullable
    String getCc();
}
