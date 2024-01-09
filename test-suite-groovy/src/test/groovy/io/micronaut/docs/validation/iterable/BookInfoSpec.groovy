package io.micronaut.docs.validation.iterable

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.validation.ConstraintViolationException
import spock.lang.Specification

@MicronautTest
class BookInfoSpec extends Specification {

    @Inject
    BookInfoService bookInfoService

    // tag::validate-iterables[]
    void testAuthorNamesAreValidated() {
        given:
        List<String> authors = ["Me", ""]

        when:
        bookInfoService.setBookAuthors("My Book", authors)

        then:
        ConstraintViolationException exception = thrown()
        "setBookAuthors.authors[1]<list element>: must not be blank" == exception.message // <1>
    }

    void testSectionsAreValidated() {
        given:
        Map<String, Integer> sectionStartPages = new HashMap<>()
        sectionStartPages.put("", 1)

        when:
        bookInfoService.setBookSectionPages("My Book", sectionStartPages)

        then:
        ConstraintViolationException exception = thrown()
        "setBookSectionPages.sectionStartPages[]<map key>: must not be blank" == exception.message // <2>
    }
    // end::validate-iterables[]
}
