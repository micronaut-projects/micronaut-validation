package io.micronaut.validation

import io.micronaut.core.beans.BeanProperty
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.validation.validator.BeanValidationContext
import io.micronaut.validation.validator.Validator
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest
class BeanValidationContextSpec
    extends Specification {

    @Inject Validator validator

    void "test skip validation of properties with custom context"() {
        given:
        Pojo pojo = new Pojo(email: "938r79l", name:"")

        when:
        def violations = validator.validate(
                pojo,
                new BeanValidationContext() {
                    def boolean isPropertyValidated(Object object, BeanProperty<Object, Object> property) {
                        return property.name == 'email'
                    }
                }
        )

        then:
        violations.size() == 1
    }

    void "test don't skip validation of properties with default context"() {
        given:
        Pojo pojo = new Pojo(email: "938r79l", name:"")

        when:
        def violations = validator.validate(
                pojo,
                BeanValidationContext.DEFAULT
        )

        then:
        violations.size() == 2
    }
}
