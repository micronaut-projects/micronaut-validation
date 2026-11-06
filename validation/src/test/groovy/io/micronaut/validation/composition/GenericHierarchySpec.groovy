package io.micronaut.validation.composition

import io.micronaut.validation.validator.DefaultValidator
import io.micronaut.validation.validator.DefaultValidatorConfiguration
import spock.lang.Specification

/**
 * The parameter constraints a generic super type declares on a type variable apply to the implementation.
 */
class GenericHierarchySpec extends Specification {

    void "a constraint on a type variable parameter of #type.simpleName applies to the implementation"() {
        given:
        def validator = new DefaultValidator(new DefaultValidatorConfiguration())
        def bean = type.getDeclaredConstructor().newInstance()
        def method = type.getMethod("save", String)

        when:
        def violations = validator.forExecutables().validateParameters(bean, method, [null] as Object[])

        then:
        violations*.constraintDescriptor*.annotation*.annotationType()*.simpleName == ["NotNull"]
        validator.getConstraintsForClass(type).getConstraintsForMethod("save", String)
            .getParameterDescriptors()[0].getConstraintDescriptors().size() == 1

        where:
        type << [GenericHierarchy.StringRepo, GenericHierarchy.StringRepoClass]
    }
}
