# tag::imports[]
from typing import Annotated

from .DurationPattern import DurationPattern
# end::imports[]


# tag::class[]
def TimeOff(duration: Annotated[str, DurationPattern]):
    def decorator(target):
        return target

    return decorator
# end::class[]
