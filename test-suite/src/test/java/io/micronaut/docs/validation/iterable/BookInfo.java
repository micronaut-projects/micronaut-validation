package io.micronaut.docs.validation.iterable;

import java.util.List;
import java.util.Map;

// tag::object[]

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class BookInfo {
    private List<@NotBlank String> authors; // <1>

    private Map<@NotBlank String, @Min(1) Integer> sectionStartPages; // <2>
}

// end:object[]
