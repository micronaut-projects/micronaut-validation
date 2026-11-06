package io.micronaut.validation.execution

import io.micronaut.context.ApplicationContext
import jakarta.validation.ConstraintViolationException
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * The executable types {@code @ValidateOnExecution} declares on a method, or else on its type, decide whether an
 * invocation is validated.
 */
class ValidateOnExecutionSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext context = ApplicationContext.run()

    void "a method declaring no executable type is not validated"() {
        expect:
        context.getBean(ExecutionTypes.MethodDeclared).skipped("") == ""
    }

    void "a method declaring nothing is validated"() {
        when:
        context.getBean(ExecutionTypes.MethodDeclared).validated("")

        then:
        thrown(ConstraintViolationException)
    }

    void "a type validating getters only validates its getters"() {
        given:
        def bean = context.getBean(ExecutionTypes.GettersOnly)

        when:
        bean.getName()

        then:
        thrown(ConstraintViolationException)

        expect:
        bean.save("") == ""
    }

    void "a method declaration wins over the one of its type"() {
        when:
        context.getBean(ExecutionTypes.GettersOnly).update("")

        then:
        thrown(ConstraintViolationException)
    }
}
