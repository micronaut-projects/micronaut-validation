package io.micronaut.validation.composition

import io.micronaut.validation.validator.DefaultValidator
import io.micronaut.validation.validator.DefaultValidatorConfiguration
import jakarta.validation.ConstraintDefinitionException
import spock.lang.PendingFeature
import spock.lang.Specification

/**
 * The declarations of a composed constraint the specification rejects.
 *
 * <p>These are checked when the constraint is described. A constraint the annotation processors compiled carries
 * the constraints it composes as a retained tree, and one they never saw does not; the checks belong to
 * describing a composed constraint and must not depend on which of the two the description was built from.
 * They are made on the reflective path only, so a constraint carrying a tree is described without them.</p>
 *
 * @see <a href="https://jakarta.ee/specifications/bean-validation/3.1/">Jakarta Validation 3.1, 3.3</a>
 */
class ComposedDeclarationSpec extends Specification {

    private static DefaultValidator validator() {
        return new DefaultValidator(new DefaultValidatorConfiguration())
    }

    void "a well formed composed constraint is described and validates, as it does today"() {
        given: "the composed constraint of the retained suite, whose declaration the specification accepts"
        def descriptors = validator().getConstraintsForClass(io.micronaut.validation.retained.Account)
            .getConstraintsForProperty("username")
            .getConstraintDescriptors()

        expect: "it is described, and the constraint it composes carries the overridden member"
        descriptors.size() == 1
        descriptors[0].getComposingConstraints()
            .collect { [it.getAnnotation().annotationType().simpleName, it.getAttributes().get("min")] } == [["Size", 8]]
    }

    @PendingFeature(reason = "the checks are made on the reflective path only, so a constraint carrying a retained tree is described without them")
    void "a member overriding a member of another type is rejected"() {
        when: "@Abc.min is a String and overrides @Size.min, which is an int"
        validator().getConstraintsForClass(ComposedBeans.WrongOverrideType)
            .getConstraintsForProperty("value")
            .getConstraintDescriptors()

        then:
        thrown(ConstraintDefinitionException)
    }

    @PendingFeature(reason = "the checks are made on the reflective path only, so a constraint carrying a retained tree is described without them")
    void "a member overriding a member the composed constraint does not declare is rejected"() {
        when:
        validator().getConstraintsForClass(ComposedBeans.OverridesMissingMember)
            .getConstraintsForProperty("value")
            .getConstraintDescriptors()

        then:
        thrown(ConstraintDefinitionException)
    }

    @PendingFeature(reason = "the checks are made on the reflective path only, so a constraint carrying a retained tree is described without them")
    void "a member overriding an occurrence the composed constraint does not have is rejected"() {
        when: "the override selects the second @Size, and one is composed"
        validator().getConstraintsForClass(ComposedBeans.OverridesAbsentOccurrence)
            .getConstraintsForProperty("value")
            .getConstraintDescriptors()

        then:
        thrown(ConstraintDefinitionException)
    }
}
