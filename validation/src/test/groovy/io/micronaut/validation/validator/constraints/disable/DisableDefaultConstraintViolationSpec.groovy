package io.micronaut.validation.validator.constraints.disable

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.validation.validator.Validator
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest(startApplication = false)
class DisableDefaultConstraintViolationSpec extends Specification {

    @Inject
    Validator validator

    void "test disableDefaultConstraintViolation"() {
        when:
            def results = validator.validate(new ValueObject(null, null, null));
            def violations = results.stream()
                    .map(x -> "" + x.getPropertyPath() + ": " + x.getMessage())
                    .toList()
        then:
            violations == ["car: must not be null",
                           "car: FromValidator",
                           "bike: must not be null",
                           "bike: FromAnnotation",
                           "truck: must not be null",
                           "truck: FromValidator"]
    }
}
