# tag::imports[]
from typing import Annotated

from jakarta.inject import Singleton
from jakarta.validation.constraints import NotBlank
# end::imports[]


# tag::class[]
@Singleton
class PersonService:
    def say_hello(self, name: Annotated[str, NotBlank]) -> None:
        print(f"Hello {name}")
# end::class[]
