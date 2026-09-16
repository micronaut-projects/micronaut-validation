# tag::imports[]
import re
from micronaut.core.annotation import AnnotationValue
from micronaut.validation.validator.constraints import ConstraintValidator, ConstraintValidatorContext

from .DurationPattern import DurationPattern
# end::imports[]


# tag::class[]
class DurationPatternValidator(ConstraintValidator[DurationPattern, str]):
    def isValid(
        self,
        value: str | None,
        annotation_metadata: AnnotationValue,
        context: ConstraintValidatorContext,
    ) -> bool:
        return value is None or re.fullmatch(r"^PT?[\d]+[SMHD]{1}$", str(value)) is not None
# end::class[]
