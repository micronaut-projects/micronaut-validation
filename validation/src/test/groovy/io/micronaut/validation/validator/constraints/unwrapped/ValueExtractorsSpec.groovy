package io.micronaut.validation.validator.constraints.unwrapped


import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.context.ApplicationContext
import io.micronaut.validation.validator.Validator
import spock.lang.AutoCleanup
import spock.lang.Shared

class ValueExtractorsSpec extends AbstractTypeElementSpec {

    @Shared
    @AutoCleanup
    ApplicationContext context = ApplicationContext.run()

    @Shared
    Validator validator = context.getBean(Validator)

    void "test @NotNull should be applied on the optional value"() {
        given:
        def introspection = buildBeanIntrospection('test.Test', """
package test;

import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import io.micronaut.validation.validator.constraints.unwrapped.MyOptional;

@io.micronaut.core.annotation.Introspected
class Test {
    @NotNull
    private MyOptional<String> field;

    public MyOptional<String> getField() {
        return field;
    }

    public void setField(MyOptional<String> f) {
        this.field = f;
    }
}
""")
        def instance = introspection.instantiate()
        def prop = introspection.getProperty("field").get()
        prop.set(instance, new MyOptional(null))
        def constraintViolations = validator.validate(introspection, instance)

        expect:
        constraintViolations.size() == 1
        constraintViolations.iterator().next().message == "must not be null"
    }
}
