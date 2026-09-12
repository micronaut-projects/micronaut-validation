package io.micronaut.validation.composition

import io.micronaut.core.beans.BeanIntrospector
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.micronaut.validation.validator.DefaultValidator
import io.micronaut.validation.validator.DefaultValidatorConfiguration
import spock.lang.Specification

/**
 * The return constraints of a hierarchy add up when the super types are introspected as well - the way the TCK
 * archive types are - so that the declaration of the super type is read from its own introspection.
 */
class ReturnHierarchyIntrospectedSpec extends Specification {

    private static Set<String> violated(Object bean) {
        def validator = new DefaultValidator(new DefaultValidatorConfiguration())
        def method = bean.getClass().getMethod("place")
        return validator.forExecutables()
            .validateReturnValue(bean, method, bean.place())
            .collect { it.getConstraintDescriptor().getAnnotation().annotationType().simpleName }
            .toSet()
    }

    void "the introspection of a super type carries the return constraint of its method"() {
        expect:
        BeanIntrospector.SHARED.getIntrospection(ReturnHierarchyIntrospected.AbstractPlacer).getBeanMethods()*.getName() == ["place"]
        BeanIntrospector.SHARED.getIntrospection(ReturnHierarchyIntrospected.AbstractPlacer).getBeanMethods()[0].getReturnType().getAnnotationMetadata().hasAnnotation(NotBlank)
        BeanIntrospector.SHARED.getIntrospection(ReturnHierarchyIntrospected.FromSuperClass).getBeanMethods()[0].getReturnType().getAnnotationMetadata().hasAnnotation(Size)
    }

    void "an introspected interface's return constraint adds up"() {
        expect:
        violated(new ReturnHierarchyIntrospected.FromInterface()) == ["NotBlank", "Size"] as Set
    }

    void "an introspected super class's return constraint adds up"() {
        expect:
        violated(new ReturnHierarchyIntrospected.FromSuperClass()) == ["NotBlank", "Size"] as Set
    }
}
