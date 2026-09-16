# tag::imports[]
from typing import Annotated

from jakarta.inject import Singleton
from jakarta.validation.constraints import NotBlank
from micronaut.validation import Validated
# end::imports[]


# tag::class[]
@Validated
@Singleton
class PersonService:
    def say_hello(self, name: Annotated[str, NotBlank]) -> None:
        print(f"Hello {name}")
# end::class[]
