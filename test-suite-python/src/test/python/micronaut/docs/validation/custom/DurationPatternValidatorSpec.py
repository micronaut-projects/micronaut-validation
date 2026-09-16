from typing import Annotated

from jakarta.inject import Inject
from jakarta.validation import ConstraintViolationException
from micronaut.test.extensions.junit5.annotation import MicronautTest
from org.junit.jupiter.api import Test

from .HolidayService import HolidayService


@MicronautTest
class DurationPatternValidatorSpec:

    # tag::test[]
    holiday_service: Annotated[HolidayService, Inject]

    @Test
    def test_custom_validator(self) -> None:
        try:
            self.holiday_service.start_holiday("Fred", "junk")  # <1>
        except ConstraintViolationException as exception:
            assert exception.getMessage() == "start_holiday.duration: invalid duration (junk), additional custom message"  # <2>
        else:
            assert False, "ConstraintViolationException expected"
    # end::test[]
