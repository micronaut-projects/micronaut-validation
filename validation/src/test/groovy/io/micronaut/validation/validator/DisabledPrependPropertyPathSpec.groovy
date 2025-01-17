package io.micronaut.validation.validator

import io.micronaut.context.ApplicationContext
import io.micronaut.validation.Validated
import jakarta.inject.Singleton
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class DisabledPrependPropertyPathSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext applicationContext = ApplicationContext.run(["micronaut.validator.prepend-property-path": false])

    void "test disabled prependPropertyPath"() {
        when:
        def service = applicationContext.getBean(MyService3)
        def bean = new MyBeanWithPrimitives()
        bean.number = 100
        service.myMethod2(bean)
        then:
        Exception e = thrown()
        e.message == 'must be less than or equal to 20'
    }
}

@Validated
@Singleton
class MyService3 {

    @Min(10)
    int myMethod1(@Max(5) int a) {
        return a
    }

    void myMethod2(@Valid MyBeanWithPrimitives bean) {
    }
}
