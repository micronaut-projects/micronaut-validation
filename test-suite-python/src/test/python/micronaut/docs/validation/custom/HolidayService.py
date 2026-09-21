from typing import Annotated

from jakarta.inject import Singleton
from jakarta.validation.constraints import NotBlank
from java.time import Duration

from .DurationPattern import DurationPattern


# tag::class[]
@Singleton
class HolidayService:

    # tag::method[]
    def start_holiday(
        self,
        person: Annotated[str, NotBlank],
        duration: Annotated[str, DurationPattern],
    ) -> str:
        d = Duration.parse(duration)
        return f"Person {person} is off on holiday for {d.toMinutes()} minutes"
    # end::method[]
# end::class[]
