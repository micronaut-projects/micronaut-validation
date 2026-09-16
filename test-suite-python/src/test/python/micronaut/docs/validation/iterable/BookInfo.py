from dataclasses import dataclass, field
from typing import Annotated

# tag::object[]

from jakarta.validation.constraints import Min, NotBlank


@dataclass
class BookInfo:
    authors: list[Annotated[str, NotBlank]] = field(default_factory=list)  # <1>

    section_start_pages: dict[Annotated[str, NotBlank], Annotated[int, Min(1)]] = field(default_factory=dict)  # <2>

# end::object[]
