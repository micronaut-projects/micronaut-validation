package io.micronaut.validation.validator.map

import io.micronaut.context.ApplicationContext
import io.micronaut.validation.validator.Validator
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class MapValidatorSpec extends Specification {
    @Shared
    @AutoCleanup
    ApplicationContext applicationContext = ApplicationContext.run()
    @Shared
    Validator validator = applicationContext.getBean(Validator)

    void "test cascade validate to map"() {
        given:
        Author a = new Author(
                "Stephen King",
                ["It": new Book("")]
        )

        when:
        def constraintViolations = validator.validate(a)

        then:
        constraintViolations.size() == 1
        constraintViolations.first().propertyPath.toString() == 'books[It].title'
    }
}


