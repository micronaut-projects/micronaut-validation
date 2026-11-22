package io.micronaut.validation.composition

import io.micronaut.validation.validator.DefaultValidator
import io.micronaut.validation.validator.DefaultValidatorConfiguration
import jakarta.validation.ConstraintDeclarationException
import jakarta.validation.ConstraintDefinitionException
import spock.lang.Specification

/**
 * The rules of a composition the retained tree of the generated metadata answers, without reflection: the
 * validation targets a composed constraint and its composing constraints share, and the type of an overridden
 * member. The processor also records the original declaration shape before containers are flattened.
 */
class IllegalCompositionSpec extends Specification {

    def validator = new DefaultValidator(new DefaultValidatorConfiguration())

    void "a member overriding a member of another type is a definition error"() {
        when:
        validator.validate(new IllegalCompositions.WithInvalidOverride())

        then:
        def e = thrown(ConstraintDefinitionException)
        e.message.contains("InvalidOverride overriding jakarta.validation.constraints.Size.min")
    }

    void "a composed constraint sharing no validation target with a composing one is a definition error"() {
        when:
        validator.getConstraintsForClass(IllegalCompositions.WithMixedTargets).getConstraintsForMethod("doSomething", int)

        then:
        def e = thrown(ConstraintDefinitionException)
        e.message.contains("share a validation target")
    }

    void "generated declaration metadata rejects direct and container composition"() {
        when:
        validator.validate(new IllegalCompositions.WithDirectAndContainer())

        then:
        def e = thrown(ConstraintDeclarationException)
        e.message.contains("both directly and in a container")
    }
}
