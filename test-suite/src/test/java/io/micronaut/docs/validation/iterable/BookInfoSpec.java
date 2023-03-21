package io.micronaut.docs.validation.iterable;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@MicronautTest
public class BookInfoSpec {

    @Inject
    BookInfoService bookInfoService;

    // tag::validate-iterables[]

    @Test
    void testAuthorNamesAreValidated() {
        final List<String> authors = Arrays.asList("Me", "");

        final ConstraintViolationException exception =
                assertThrows(ConstraintViolationException.class, () ->
                        bookInfoService.setBookAuthors("My Book", authors)
                );

        assertEquals("setBookAuthors.authors[1]<list element>: must not be blank",
                exception.getMessage()); // <1>
    }

    @Test
    void testSectionsAreValidated() {
        final Map<String, Integer> sectionStartPages = new HashMap<>();
        sectionStartPages.put("", 1);

        final ConstraintViolationException exception =
                assertThrows(ConstraintViolationException.class, () ->
                        bookInfoService.setBookSectionPages("My Book", sectionStartPages)
                );

        assertEquals("setBookSectionPages.sectionStartPages[]<map key>: must not be blank",
                exception.getMessage()); // <2>
    }

    // end::validate-iterables[]
}
