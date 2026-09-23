# tag::imports[]
import re
from jakarta.inject import Singleton
from micronaut.context.annotation import Factory
from micronaut.validation.validator.constraints import ConstraintValidator

from .DurationPattern import DurationPattern
# end::imports[]


# tag::class[]
@Factory
class MyValidatorFactory:

    @Singleton
    def duration_pattern_validator(self) -> ConstraintValidator[DurationPattern, object]:
        def is_valid(value, annotation_metadata, context) -> bool:
            context.messageTemplate("invalid duration ({validatedValue}), additional custom message")  # <1>
            return value is None or re.fullmatch(r"^PT?[\d]+[SMHD]{1}$", str(value)) is not None

        return is_valid
# end::class[]
