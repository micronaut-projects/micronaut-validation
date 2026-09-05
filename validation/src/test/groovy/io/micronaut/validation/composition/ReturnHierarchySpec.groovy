package io.micronaut.validation.composition

import io.micronaut.validation.validator.DefaultValidator
import io.micronaut.validation.validator.DefaultValidatorConfiguration
import spock.lang.PendingFeature
import spock.lang.Specification

/**
 * The constraints of every level of a hierarchy add up on the return value of an overriding method: the one an
 * interface or a super class declares is validated next to the one the implementation declares, not replaced
 * by it.
 *
 * @see <a href="https://jakarta.ee/specifications/bean-validation/3.1/">Jakarta Validation 3.1, 4.5.5</a>
 */
class ReturnHierarchySpec extends Specification {

    private static Set<String> violated(Object bean) {
        def validator = new DefaultValidator(new DefaultValidatorConfiguration())
        def method = bean.getClass().getMethod("place")
        return validator.forExecutables()
            .validateReturnValue(bean, method, bean.place())
            .collect { it.getConstraintDescriptor().getAnnotation().annotationType().simpleName }
            .toSet()
    }

    @PendingFeature(reason = "only the constraint the override declares is validated; the level above it is lost")
    void "the constraint an implemented interface declares is validated next to the implementation's"() {
        expect:
        violated(new ReturnHierarchy.FromInterface()) == ["NotBlank", "Size"] as Set
    }

    @PendingFeature(reason = "only the constraint the override declares is validated; the level above it is lost")
    void "the constraint a super class declares is validated next to the subclass's"() {
        expect:
        violated(new ReturnHierarchy.FromSuperClass()) == ["NotBlank", "Size"] as Set
    }
}
