# tag::imports[]
from typing import Annotated

from jakarta.inject import Singleton
from jakarta.validation import Valid
from micronaut.docs.validation.Person import Person
from micronaut.validation import Validated
# end::imports[]


# tag::class[]
@Validated
@Singleton
class PersonService:
    def say_hello(self, person: Annotated[Person, Valid]) -> None:
        print(f"Hello {person.name}")
# end::class[]
