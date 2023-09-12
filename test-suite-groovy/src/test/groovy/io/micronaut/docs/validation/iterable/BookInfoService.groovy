package io.micronaut.docs.validation.iterable

import jakarta.inject.Singleton
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

// tag::validate-iterables[]

@Singleton
class BookInfoService {
    void setBookAuthors(
        @NotBlank String bookName,
        List<@NotBlank String> authors // <1>
    ) {
        println("Set book authors for book " + bookName)
    }

    void setBookSectionPages(
        @NotBlank String bookName,
        Map<@NotBlank String, @Min(1) Integer> sectionStartPages // <2>
    ) {
        println("Set the start pages for all sections of book " + bookName)
    }
}

// end::validate-iterables[]

