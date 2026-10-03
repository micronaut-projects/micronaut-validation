# tag::imports[]
from typing import Annotated

from jakarta.inject import Inject
from jakarta.validation import ConstraintViolationException
from micronaut.docs.validation.Person import Person
from micronaut.test.extensions.junit5.annotation import MicronautTest
from micronaut.validation.validator import Validator
from org.junit.jupiter.api import Test

from .PersonService import PersonService
# end::imports[]


@MicronautTest
class PersonServiceSpec:

    # tag::validator[]
    validator: Annotated[Validator, Inject]

    @Test
    def test_that_person_is_valid_with_validator(self) -> None:
        person = Person(name="", age=10)

        constraint_violations = self.validator.validate(person)  # <1>

        assert constraint_violations.size() == 2  # <2>
    # end::validator[]

    # tag::validate-service[]
    person_service: Annotated[PersonService, Inject]

    @Test
    def test_that_person_is_valid(self) -> None:
        person = Person(name="", age=10)

        try:
            self.person_service.say_hello(person)  # <1>
        except ConstraintViolationException as exception:
            assert exception.getConstraintViolations().size() == 2  # <2>
        else:
            assert False, "ConstraintViolationException expected"
    # end::validate-service[]
