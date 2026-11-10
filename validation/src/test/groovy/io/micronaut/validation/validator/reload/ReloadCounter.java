package io.micronaut.validation.validator.reload;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class ReloadCounter {
    @ReloadOdd
    private final Integer count;

    public ReloadCounter(Integer count) {
        this.count = count;
    }

    public Integer getCount() {
        return count;
    }
}
