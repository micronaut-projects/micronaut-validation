package io.micronaut.validation;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Requires;

import javax.validation.constraints.NotBlank;

@ConfigurationProperties("my.config")
@Requires(property = "my.config")
public interface MyConfig {
    @NotBlank
    String getName();
}
