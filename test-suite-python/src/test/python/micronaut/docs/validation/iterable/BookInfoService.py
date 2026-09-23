from typing import Annotated

from jakarta.inject import Singleton
from jakarta.validation.constraints import Min, NotBlank

# tag::validate-iterables[]

@Singleton
class BookInfoService:
    def set_book_authors(
        self,
        book_name: Annotated[str, NotBlank],
        authors: list[Annotated[str, NotBlank]],  # <1>
    ) -> None:
        print(f"Set book authors for book {book_name}")

    def set_book_section_pages(
        self,
        book_name: Annotated[str, NotBlank],
        section_start_pages: dict[Annotated[str, NotBlank], Annotated[int, Min(1)]],  # <2>
    ) -> None:
        print(f"Set the start pages for all sections of book {book_name}")

# end::validate-iterables[]
