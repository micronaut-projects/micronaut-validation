package io.micronaut.validation.validator.replacement

import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInvocationContext
import io.micronaut.context.annotation.Replaces
import io.micronaut.core.beans.BeanProperty
import io.micronaut.core.convert.ConversionService
import io.micronaut.test.annotation.MockBean
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.validation.Pojo
import io.micronaut.validation.Validated
import io.micronaut.validation.ValidatingInterceptor
import io.micronaut.validation.validator.BeanValidationContext
import io.micronaut.validation.validator.Validator
import io.micronaut.validation.validator.ValidatorConfiguration
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Valid
import jakarta.validation.ValidatorFactory
import spock.lang.Specification

@MicronautTest
class ReplaceInterceptorSpec extends Specification {

    @Inject TestService testService

    void "test replacement interceptor"() {
        when:
        testService.validatePojo(new Pojo(email: "junk", name: ""))

        then:
        def e = thrown(ConstraintViolationException)
        e.message == 'validatePojo.pojo.email: Email should be valid'
    }

    @MockBean(ValidatingInterceptor)
    @InterceptorBean(Validated)
    static class MyInterceptor extends ValidatingInterceptor {
        Validator micronautValidator
        MyInterceptor(Validator micronautValidator, ValidatorFactory validatorFactory, ConversionService conversionService, ValidatorConfiguration validatorConfiguration) {
            super(micronautValidator, validatorFactory, conversionService, validatorConfiguration)
            this.micronautValidator = micronautValidator;
        }

        @Override
        Object intercept(MethodInvocationContext<Object, Object> context) {
            if (context.parameterValues[0] instanceof Pojo) {
                def constraintViolations = micronautValidator.forExecutables()
                        .validateParameters(
                                context.parameterValues[0],
                                context.executableMethod,
                                context.parameterValues,
                                new BeanValidationContext() {
                                    boolean isPropertyValidated(Object object, BeanProperty<Object, Object> property) {
                                        return property.name == 'email'
                                    }
                                }
                        )
                if (constraintViolations) {
                    throw new ConstraintViolationException(constraintViolations)
                }
                return  context.proceed()
            } else {
                return super.intercept(context)
            }
        }
    }

    @Singleton
    @Validated
    static class TestService {
        void validatePojo(@Valid Pojo pojo) {
            println(pojo)
        }
    }
}
