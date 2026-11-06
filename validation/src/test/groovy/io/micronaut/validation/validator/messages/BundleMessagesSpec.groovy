package io.micronaut.validation.validator.messages

import io.micronaut.context.ApplicationContext
import io.micronaut.validation.validator.Validator
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A bundle message is interpolated by the rules of the specification: a quote stays, an unknown parameter stays a
 * parameter, a message parameter of it is resolved from the bundles too.
 */
class BundleMessagesSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext context = ApplicationContext.run(["spec.name": "BundleMessagesSpec"])

    void "bundle messages keep quotes and unknown parameters"() {
        given:
        def messages = context.getBean(Validator).validate(new BundleMessages.Person())
            .collectEntries { [(it.propertyPath.toString()): it.message] }

        expect:
        messages == [
            name : "Name can't be blank",
            nick : "at least 2, not {unknown}",
            other: "see Name can't be blank"
        ]
    }
}
