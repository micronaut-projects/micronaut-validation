package io.micronaut.docs.validation.iterable

import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.validation.ConstraintViolationException
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

@MicronautTest
class BookInfoSpec {
    @Inject
    lateinit var bookInfoService: BookInfoService

    // tag::validate-iterables[]
    @Test
    fun testAuthorNamesAreValidated() {
        val authors: List<String> = mutableListOf("Me", "")
        val exception = Assertions.assertThrows(
            ConstraintViolationException::class.java
        ) { bookInfoService.setBookAuthors("My Book", authors) }
        Assertions.assertEquals(
            "setBookAuthors.authors[1]<list element>: must not be blank",
            exception.message
        ) // <1>
    }

    @Test
    fun testSectionsAreValidated() {
        val sectionStartPages: MutableMap<String, Int> = HashMap()
        sectionStartPages[""] = 1
        val exception = Assertions.assertThrows(
            ConstraintViolationException::class.java
        ) {
            bookInfoService.setBookSectionPages(
                "My Book",
                sectionStartPages
            )
        }
        Assertions.assertEquals(
            "setBookSectionPages.sectionStartPages[]<map key>: must not be blank",
            exception.message
        ) // <2>
    } // end::validate-iterables[]
}
