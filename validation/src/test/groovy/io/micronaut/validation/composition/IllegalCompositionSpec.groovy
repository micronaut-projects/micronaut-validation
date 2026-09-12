package io.micronaut.validation.composition

import io.micronaut.validation.validator.DefaultValidator
import io.micronaut.validation.validator.DefaultValidatorConfiguration
import jakarta.validation.ConstraintDeclarationException
import jakarta.validation.ConstraintDefinitionException
import spock.lang.Specification

/**
 * The rules of a composition the retained tree of the generated metadata answers, without reflection: the
 * validation targets a composed constraint and its composing constraints share, and the type of an overridden
 * member. The rule only the declared form of the annotation type answers - a constraint composed both directly
 * and inside its container - is checked by the reflection module, which these tests run without.
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

    void "a constraint composed both directly and in its container is not told from the tree alone"() {
        when: "the container is flattened into repeated occurrences by the processor"
        def violations = validator.validate(new IllegalCompositions.WithDirectAndContainer())

        then: "without the reflection module the composition validates as two patterns"
        notThrown(ConstraintDeclarationException)
        violations*.constraintDescriptor*.annotation*.annotationType()*.simpleName.sort() == ["Pattern", "Pattern"]
    }
}
