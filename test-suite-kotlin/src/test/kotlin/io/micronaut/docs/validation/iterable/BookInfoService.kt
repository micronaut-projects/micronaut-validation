package io.micronaut.docs.validation.iterable

import jakarta.inject.Singleton
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

// tag::validate-iterables[]
@Singleton
open class BookInfoService {
    open fun setBookAuthors(
        bookName: @NotBlank String,
        authors: List<@NotBlank String> // <1>
    ) {
        println("Set book authors for book $bookName")
    }

    open fun setBookSectionPages(
        bookName: @NotBlank String,
        sectionStartPages: Map<@NotBlank String, @Min(1) Int>  // <2>
    ) {
        println("Set the start pages for all sections of book $bookName")
    }
}

// end::validate-iterables[]
