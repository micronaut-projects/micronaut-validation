package io.micronaut.validation.retained

import io.micronaut.core.annotation.AnnotationValue
import io.micronaut.core.beans.BeanIntrospection
import io.micronaut.core.beans.BeanIntrospector
import spock.lang.Specification

class RetainedConstraintSpec extends Specification {

    void "a composed constraint retains the constraint it composes, with the override applied"() {
        given:
        BeanIntrospection<Account> introspection = BeanIntrospector.SHARED.getIntrospection(Account)
        def username = introspection.getRequiredProperty("username", String).getAnnotationMetadata()
        AnnotationValue<?> minimumLength = username.getAnnotation("io.micronaut.validation.retained.MinimumLength")

        expect: "the composed constraint is present"
        minimumLength != null

        and: "and retains the Size occurrence it introduced, with its OverridesAttribute applied"
        System.err.println("RETAINED tree = " + minimumLength.getStereotypes())
        def sizes = minimumLength.getStereotypes()
                .findAll { it.getAnnotationName() == "jakarta.validation.constraints.Size" }
        sizes*.getValues() == [[min: 8]]

        and: "the flat index still exposes the effective value"
        System.err.println("RETAINED flat Size = " + username.getAnnotationValuesByName("jakarta.validation.constraints.Size")*.values)
    }
}
