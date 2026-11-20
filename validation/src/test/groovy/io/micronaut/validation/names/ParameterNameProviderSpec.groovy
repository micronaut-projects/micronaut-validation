package io.micronaut.validation.names

import io.micronaut.context.ApplicationContext
import jakarta.validation.ConstraintViolationException
import spock.lang.Specification

/**
 * An application naming parameters itself keeps its advised methods validated: without a Java method at hand
 * the names compiled into the metadata stand.
 */
class ParameterNameProviderSpec extends Specification {

    void "an advised method is validated with an application parameter name provider"() {
        given:
        def context = ApplicationContext.run(["spec.name": "ParameterNameProviderSpec"])
        def service = context.getBean(NamingService)

        when:
        service.save("")

        then:
        ConstraintViolationException e = thrown()
        e.constraintViolations.size() == 1

        cleanup:
        context.close()
    }
}
