package io.micronaut.validation.validator;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.Validated;
import jakarta.validation.constraints.Size;

@Validated
@Introspected
class MyBook {
    @Size(max = 2, message = "Check path: {validatedPath} with value: {validatedValue}")
    private String title;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
