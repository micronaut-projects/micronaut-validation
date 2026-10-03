# tag::imports[]
from jakarta.validation import Constraint

from .MyValidatorBean import MyValidatorBean
# end::imports[]


# tag::class[]
@Constraint(validatedBy=[MyValidatorBean])  # <1>
def DurationPattern(message: str = "invalid duration ({validatedValue})"):  # <2>
    def decorator(target):
        return target

    return decorator
# end::class[]
