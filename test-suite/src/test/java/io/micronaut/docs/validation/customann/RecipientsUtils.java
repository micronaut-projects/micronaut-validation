package io.micronaut.docs.validation.customann;

import org.jspecify.annotations.NonNull;
import io.micronaut.core.util.StringUtils;

public final class RecipientsUtils {
    public static boolean isValid(@NonNull Recipients recipients) {
        return StringUtils.isNotEmpty(recipients.getTo()) ||
                StringUtils.isNotEmpty(recipients.getCc());
    }
}
