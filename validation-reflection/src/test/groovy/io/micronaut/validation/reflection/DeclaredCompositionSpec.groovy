package io.micronaut.validation.reflection

import io.micronaut.validation.validator.DefaultValidator
import io.micronaut.validation.validator.DefaultValidatorConfiguration
import jakarta.validation.ConstraintDeclarationException
import jakarta.validation.ConstraintDefinitionException
import spock.lang.Specification

/**
 * The rules of a composition only the declared form of the annotation type answers, checked through the
 * reflection module: a constraint composed both directly and inside its container, which the retained tree
 * flattens into repeated occurrences, and an override naming a member or an occurrence the composing
 * constraint does not have, which the tree cannot tell from a member without a default or from the
 * occurrence it does have.
 */
class DeclaredCompositionSpec extends Specification {

    void "a constraint composed both directly and in its container is a declaration error"() {
        when:
        new DefaultValidator(new DefaultValidatorConfiguration()).validate(new WithDirectAndContainer())

        then:
        def e = thrown(ConstraintDeclarationException)
        e.message.contains("both directly and in a container")
    }

    void "a member overriding a member the composed constraint does not declare is rejected"() {
        when:
        new DefaultValidator(new DefaultValidatorConfiguration()).getConstraintsForClass(OverrideBeans.OverridesMissingMember)
            .getConstraintsForProperty("value")
            .getConstraintDescriptors()

        then:
        thrown(ConstraintDefinitionException)
    }

    void "a member overriding an occurrence the composed constraint does not have is rejected"() {
        when: "the override selects the second @Size, and one is composed"
        new DefaultValidator(new DefaultValidatorConfiguration()).getConstraintsForClass(OverrideBeans.OverridesAbsentOccurrence)
            .getConstraintsForProperty("value")
            .getConstraintDescriptors()

        then:
        thrown(ConstraintDefinitionException)
    }
}
