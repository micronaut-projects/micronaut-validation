package io.micronaut.docs.validation.iterable;

import jakarta.inject.Singleton;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

// tag::validate-iterables[]

@Singleton
public class BookInfoService {
    public void setBookAuthors(
        @NotBlank String bookName,
        List<@NotBlank String> authors // <1>
    ) {
        System.out.println("Set book authors for book " + bookName);
    }

    public void setBookSectionPages(
        @NotBlank String bookName,
        Map<@NotBlank String, @Min(1) Integer> sectionStartPages // <2>
    ) {
        System.out.println("Set the start pages for all sections of book " + bookName);
    }
}

// end::validate-iterables[]

