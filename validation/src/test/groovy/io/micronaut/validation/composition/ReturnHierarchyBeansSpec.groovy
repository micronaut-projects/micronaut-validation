package io.micronaut.validation.composition

import io.micronaut.context.ApplicationContext
import io.micronaut.inject.ExecutableMethod
import io.micronaut.validation.validator.Validator
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * The return constraints of a hierarchy add up when the types are beans: the method validated is the one of
 * the bean definition, whose metadata already merges what the super type declares, and the super type's own
 * declaration is read from its introspection next to it.
 */
class ReturnHierarchyBeansSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    private Set<String> violated(Class<?> type) {
        def bean = context.getBean(type)
        def validator = context.getBean(Validator)
        def method = type.getMethod("place")
        return validator.forExecutables()
            .validateReturnValue(bean, method, "")
            .collect { it.getConstraintDescriptor().getAnnotation().annotationType().simpleName }
            .toSet()
    }

    void "what the bean definition says"() {
        given:
        ExecutableMethod method = context.getBeanDefinition(ReturnHierarchyBeans.FromSuperClass).findMethod("place").get()

        expect: "the processor merges the inherited constraint into the method of the definition"
        method.getAnnotationMetadata().getAnnotationNames().findAll { it.startsWith("jakarta") }.sort() == ["jakarta.validation.constraints.NotBlank\$List", "jakarta.validation.constraints.Size\$List"]
    }

    void "an introspected interface's return constraint adds up for a bean"() {
        expect:
        violated(ReturnHierarchyBeans.FromInterface) == ["NotBlank", "Size"] as Set
    }

    void "an introspected super class's return constraint adds up for a bean"() {
        expect:
        violated(ReturnHierarchyBeans.FromSuperClass) == ["NotBlank", "Size"] as Set
    }
}
