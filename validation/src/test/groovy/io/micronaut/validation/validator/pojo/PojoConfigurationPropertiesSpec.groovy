package io.micronaut.validation.validator.pojo

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Executable
import io.micronaut.context.exceptions.BeanInstantiationException
import io.micronaut.validation.Pojo
import jakarta.inject.Singleton
import spock.lang.Specification

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Valid


class PojoConfigurationPropertiesSpec extends Specification {

    ApplicationContext context = ApplicationContext.run([
            'test.valid.pojos': [
                    [name: '']
            ]
    ])

    void "test @Valid on config props property manual"() {
        when:
        Pojo pojo = new Pojo()
        pojo.name = ""
        PojoConfigProps configProps = new PojoConfigProps()
        configProps.pojos = [pojo]

        context.getBean(PojoConfigPropsValidator).validateConfigProps(configProps)

        then:
        def ex = thrown(ConstraintViolationException)
        ex.message.contains("must not be blank")
    }

    void "test @Valid on config props property"() {
        when:
        context.getBean(PojoConfigProps)

        then:
        def ex = thrown(BeanInstantiationException)
        ex.message.contains("List of constraint violations:[\n\tpojos[0].name - must not be blank\n]")
    }
}

@Singleton
class PojoConfigPropsValidator {
    @Executable
    void validateConfigProps(@Valid PojoConfigProps configProps) {}
}
