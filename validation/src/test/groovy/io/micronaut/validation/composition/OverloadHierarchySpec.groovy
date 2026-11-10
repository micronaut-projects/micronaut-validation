package io.micronaut.validation.composition

import io.micronaut.validation.validator.DefaultValidator
import io.micronaut.validation.validator.DefaultValidatorConfiguration
import spock.lang.Specification

/**
 * An overload is not an override: it inherits no constraint of the super method it does not override.
 */
class OverloadHierarchySpec extends Specification {

    void "an overload with an assignable parameter inherits nothing"() {
        given:
        def validator = new DefaultValidator(new DefaultValidatorConfiguration())
        def bean = new OverloadHierarchy.Impl()

        expect:
        validator.forExecutables().validateParameters(bean, OverloadHierarchy.Impl.getMethod("save", String), [null] as Object[]).isEmpty()
        validator.forExecutables().validateParameters(bean, OverloadHierarchy.Impl.getMethod("save", Object), [null] as Object[]).size() == 1
        validator.getConstraintsForClass(OverloadHierarchy.Impl).getConstraintsForMethod("save", String) == null
    }

    void "an advised overload with an assignable parameter inherits nothing"() {
        given:
        def context = io.micronaut.context.ApplicationContext.run()
        def service = context.getBean(OverloadHierarchy.Service)

        when:
        service.save((String) null)

        then:
        noExceptionThrown()

        when:
        service.save((Object) null)

        then:
        thrown(jakarta.validation.ConstraintViolationException)

        cleanup:
        context.close()
    }
}
