package io.micronaut.validation.validator.messages

import io.micronaut.context.ApplicationContext
import io.micronaut.validation.validator.Validator
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class ArrayAndClassTextSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext context = ApplicationContext.run(["spec.name": "ArrayAndClassTextSpec"])

    void "an array attribute is interpolated by its elements"() {
        when:
        def violations = context.getBean(Validator).validate(new ArrayAndClassTextBeans.Flagged())

        then:
        violations*.message == ["flags [CASE_INSENSITIVE, MULTILINE]"]
    }

    void "a regexp starting with 'class ' is not mistaken for a class name"() {
        when:
        def violations = context.getBean(Validator).validate(new ArrayAndClassTextBeans.TwoPatterns())

        then:
        violations.size() == 2
        violations*.constraintDescriptor*.attributes*.regexp as Set == ["class \\d+", "\\d+"] as Set
    }
}
