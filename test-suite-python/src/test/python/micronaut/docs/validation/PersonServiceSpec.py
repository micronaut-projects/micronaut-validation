# tag::imports[]
from typing import Annotated

from jakarta.inject import Inject
from jakarta.validation import ConstraintViolationException
from micronaut.test.extensions.junit5.annotation import MicronautTest
from org.junit.jupiter.api import Test

from .PersonService import PersonService
# end::imports[]


# tag::test[]
@MicronautTest
class PersonServiceSpec:
    person_service: Annotated[PersonService, Inject]

    @Test
    def test_that_name_is_validated(self) -> None:
        try:
            self.person_service.say_hello("")  # <1>
        except ConstraintViolationException as exception:
            assert exception.getMessage() == "say_hello.name: must not be blank"  # <2>
        else:
            assert False, "ConstraintViolationException expected"
# end::test[]
