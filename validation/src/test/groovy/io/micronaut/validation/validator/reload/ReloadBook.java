package io.micronaut.validation.validator.reload;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Size;

@Introspected
public class ReloadBook {
    @Size(max = 3)
    private final String title;
    @ReloadEven(groups = ReloadChecks.class)
    private final Integer pages;

    public ReloadBook(String title, Integer pages) {
        this.title = title;
        this.pages = pages;
    }

    public String getTitle() {
        return title;
    }

    public Integer getPages() {
        return pages;
    }
}
