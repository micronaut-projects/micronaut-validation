package io.micronaut.validation

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton
import jakarta.validation.constraints.NotBlank
import org.reactivestreams.Publisher
import spock.lang.Issue
import spock.lang.Specification

class ValidatedInterfaceSpec extends Specification {
    @Issue('https://github.com/micronaut-projects/micronaut-core/issues/9418')
    def 'error should be thrown directly'() {
        given:
        def ctx = ApplicationContext.run(['spec.name': 'ValidatedInterfaceSpec'])
        def itf = ctx.getBean(MyInterface)

        when:
        itf.error("xyz")
        then:
        thrown IllegalStateException

        cleanup:
        ctx.close()
    }

    interface MyInterface {
        Publisher<String> error(@NotBlank String someValue)
    }

    @Singleton
    @Requires(property = "spec.name", value = "ValidatedInterfaceSpec")
    static class MyImpl implements MyInterface {
        @Override
        Publisher<String> error(String someValue) {
            throw new IllegalStateException()
        }
    }
}
