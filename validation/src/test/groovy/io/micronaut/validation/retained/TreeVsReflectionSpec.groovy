package io.micronaut.validation.retained

import io.micronaut.core.annotation.AnnotationValue
import io.micronaut.core.beans.BeanIntrospector
import io.micronaut.validation.validator.DefaultValidator
import io.micronaut.validation.validator.DefaultValidatorConfiguration
import jakarta.validation.metadata.ConstraintDescriptor
import spock.lang.Specification

/**
 * Compares what the reflective getComposingConstraints() produces with what a walk over the retained tree
 * produces, to see whether the tree could replace the reflection.
 */
class TreeVsReflectionSpec extends Specification {

    void "the retained tree yields the same composing constraints as the reflective implementation"() {
        given:
        def validator = new DefaultValidator(new DefaultValidatorConfiguration())
        def descriptor = validator.getConstraintsForClass(Account)
                .getConstraintsForProperty("username")

        when: "what the reflective implementation reports"
        Set<ConstraintDescriptor<?>> constraints = descriptor.getConstraintDescriptors()
        def minimumLength = constraints.find { it.getAnnotation().annotationType().simpleName == "MinimumLength" }
        def reflective = minimumLength.getComposingConstraints()
                .collect { [it.getAnnotation().annotationType().name, it.getAttributes().findAll { k, v -> k == "min" }] }

        and: "what a walk over the retained tree reports"
        def metadata = BeanIntrospector.SHARED.getIntrospection(Account)
                .getRequiredProperty("username", String).getAnnotationMetadata()
        AnnotationValue<?> composed = metadata.getAnnotation("io.micronaut.validation.retained.MinimumLength")
        def tree = composed.getStereotypes()
                .findAll { s -> s.getStereotypes() != null && s.getStereotypes()*.getAnnotationName().contains("jakarta.validation.Constraint") }
                .collect { [it.getAnnotationName(), it.getValues().findAll { k, v -> k == "min" }] }

        then: "the tree answers what the reflection answers"
        tree == reflective
        tree == [["jakarta.validation.constraints.Size", [min: 8]]]

        and: "the constraint contract is what identifies a composing occurrence as a constraint"
        composed.getStereotypes().find { it.getAnnotationName() == "jakarta.validation.constraints.Size" }
                .getStereotypes()*.getAnnotationName() == ["jakarta.validation.Constraint"]

        and: "and the contract itself keeps no subtree, so it is not mistaken for one"
        composed.getStereotypes().find { it.getAnnotationName() == "jakarta.validation.Constraint" }
                .getStereotypes() == null
    }
}
