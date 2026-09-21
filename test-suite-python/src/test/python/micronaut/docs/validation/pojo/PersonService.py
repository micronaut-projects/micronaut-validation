# tag::imports[]
from typing import Annotated

from jakarta.inject import Singleton
from jakarta.validation import Valid
from micronaut.docs.validation.Person import Person
# end::imports[]


# tag::class[]
@Singleton
class PersonService:
    def say_hello(self, person: Annotated[Person, Valid]) -> None:
        print(f"Hello {person.name}")
# end::class[]
