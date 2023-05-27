package io.micronaut.docs.validation.records;

import java.util.List;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@ConfigurationProperties("test")
public record MyRecordConfig(
    @NotBlank String url,
    @Valid List<@NotBlank String> urls,
    @Valid NestedConfig nestedConfig ) {


    @ConfigurationProperties("nested")
    public record NestedConfig(@Positive int port) {

    }
}
