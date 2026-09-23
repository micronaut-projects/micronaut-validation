from typing import Annotated

from jakarta.inject import Inject
from jakarta.validation import ConstraintViolationException
from micronaut.test.extensions.junit5.annotation import MicronautTest
from org.junit.jupiter.api import Test

from .BookInfoService import BookInfoService


@MicronautTest
class BookInfoSpec:

    book_info_service: Annotated[BookInfoService, Inject]

    # tag::validate-iterables[]

    @Test
    def test_author_names_are_validated(self) -> None:
        authors = ["Me", ""]

        try:
            self.book_info_service.set_book_authors("My Book", authors)
        except ConstraintViolationException as exception:
            assert exception.getMessage() == "set_book_authors.authors[1]<list element>: must not be blank"  # <1>
        else:
            assert False, "ConstraintViolationException expected"

    @Test
    def test_sections_are_validated(self) -> None:
        section_start_pages = {"": 1}

        try:
            self.book_info_service.set_book_section_pages("My Book", section_start_pages)
        except ConstraintViolationException as exception:
            assert exception.getMessage() == "set_book_section_pages.section_start_pages[]<map key>: must not be blank"  # <2>
        else:
            assert False, "ConstraintViolationException expected"

    # end::validate-iterables[]
