package io.micronaut.docs.validation.path.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.docs.validation.path.validations.WorkerGroupValidation;
import jakarta.validation.constraints.Pattern;

@Introspected
@WorkerGroupValidation
public class WorkerGroup {
    @Pattern(regexp = "[a-zA-Z0-9_-]+")
    private String key;

    public WorkerGroup(@Pattern(regexp = "[a-zA-Z0-9_-]+") String key) {
        this.key = key;
    }

    public WorkerGroup() {
    }

    public @Pattern(regexp = "[a-zA-Z0-9_-]+") String getKey() {
        return this.key;
    }
}
