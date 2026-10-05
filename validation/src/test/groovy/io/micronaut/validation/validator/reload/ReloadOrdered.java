package io.micronaut.validation.validator.reload;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.GroupSequence;
import jakarta.validation.groups.Default;

@Introspected
@GroupSequence({Default.class, ReloadChecks.class})
public interface ReloadOrdered {
}
