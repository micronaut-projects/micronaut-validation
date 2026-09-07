package io.micronaut.validation.reflection

import io.micronaut.validation.validator.DefaultValidator
import io.micronaut.validation.validator.DefaultValidatorConfiguration
import jakarta.validation.ConstraintDeclarationException
import spock.lang.Specification

/**
 * The rule of a composition only the declared form of the annotation type answers, checked through the
 * reflection module: a constraint composed both directly and inside its container, which the retained tree
 * flattens into repeated occurrences.
 */
class DeclaredCompositionSpec extends Specification {

    void "a constraint composed both directly and in its container is a declaration error"() {
        when:
        new DefaultValidator(new DefaultValidatorConfiguration()).validate(new WithDirectAndContainer())

        then:
        def e = thrown(ConstraintDeclarationException)
        e.message.contains("both directly and in a container")
    }
}
