package io.micronaut.validation.validator.constraints.custom

import io.micronaut.context.ApplicationContext
import io.micronaut.validation.validator.Validator
import spock.lang.Specification

@io.micronaut.core.annotation.Introspected
@CustomMessageConstraint2
class CustomInvalidOuter2 { }

class JakartaCustomConstraintsSpec extends Specification {
    void "test validation bean where outer custom message constraint fails"() {
        given:
        CustomInvalidOuter2 invalidOuter = new CustomInvalidOuter2()

        when:
        def violations = applicationContext.getBean(Validator).validate(invalidOuter)

        then:
        violations.size() == 1
        violations[0].message == "custom invalid"
    }

    def applicationContext = ApplicationContext.run()
    def cleanup() { applicationContext.close() }
}
