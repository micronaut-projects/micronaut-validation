# tag::imports[]
import re
from typing import TYPE_CHECKING

from jakarta.inject import Singleton
from micronaut.core.annotation import AnnotationValue
from micronaut.validation.validator.constraints import ConstraintValidator, ConstraintValidatorContext

if TYPE_CHECKING:
    from .DurationPattern import DurationPattern
# end::imports[]


# tag::class[]
@Singleton
class MyValidatorBean(ConstraintValidator["DurationPattern", object]):

    def isValid(
        self,
        value: object | None,
        annotation_metadata: AnnotationValue,
        context: ConstraintValidatorContext,
    ) -> bool:
        context.messageTemplate("invalid duration ({validatedValue}), additional custom message")  # <1>
        return value is None or re.fullmatch(r"^PT?[\d]+[SMHD]{1}$", str(value)) is not None
# end::class[]
