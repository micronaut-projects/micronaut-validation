package io.micronaut.docs.validation.customann;

import io.micronaut.core.annotation.Nullable;
public interface Recipients {

    @Nullable
    String getTo();

    @Nullable
    String getCc();
}
