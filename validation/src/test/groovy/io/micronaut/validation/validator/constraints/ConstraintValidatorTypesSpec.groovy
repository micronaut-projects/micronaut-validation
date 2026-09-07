package io.micronaut.validation.validator.constraints

import io.micronaut.core.beans.BeanIntrospector
import io.micronaut.validation.annotation.ConstraintValidatorTypes
import jakarta.validation.constraints.Size
import spock.lang.Specification

/**
 * An introspected validator is described from its introspection: the processor records the constraint and the
 * validated type, and the resolver reads them back without the generic signature of the class.
 */
class ConstraintValidatorTypesSpec extends Specification {

    void "the processor records the types an introspected validator binds"() {
        given:
        def introspection = BeanIntrospector.SHARED.getIntrospection(IntrospectedLengthValidator)

        expect:
        introspection.getAnnotationMetadata().classValue(ConstraintValidatorTypes, "constraint").get() == Size
        introspection.getAnnotationMetadata().classValue(ConstraintValidatorTypes, "target").get() == CharSequence
    }

    void "the resolver answers from the introspection"() {
        expect:
        ConstraintValidatorTargetResolver.getTargetType(BeanIntrospector.SHARED.getIntrospection(IntrospectedLengthValidator)) == CharSequence
        ConstraintValidatorTargetResolver.getTargetType(IntrospectedLengthValidator) == CharSequence
    }
}
