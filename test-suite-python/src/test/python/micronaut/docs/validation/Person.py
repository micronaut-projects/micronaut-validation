# tag::class[]
from dataclasses import dataclass
from typing import Annotated

from jakarta.validation.constraints import Min, NotBlank
from micronaut.core.annotation import Introspected


@Introspected
@dataclass
class Person:
    name: Annotated[str | None, NotBlank] = None
    age: Annotated[int, Min(18)] = 0
# end::class[]
