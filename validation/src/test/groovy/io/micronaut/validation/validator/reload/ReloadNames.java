package io.micronaut.validation.validator.reload;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class ReloadNames {
    public List<@NotBlank String> names(List<@NotBlank String> names) {
        return names;
    }
}
