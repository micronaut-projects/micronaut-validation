package io.micronaut.docs.validation.iterable

// tag::object[]
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

class BookInfo {
    List<@NotBlank String> authors // <1>
    Map<@NotBlank String, @Min(1) Integer> sectionStartPages // <2>
}
// end:object[]
