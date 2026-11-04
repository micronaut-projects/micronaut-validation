package io.micronaut.validation.validator.reload;

import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotBlank;

@Singleton
public class ReloadService {
    @NotBlank
    public String name() {
        return "reload";
    }
}
