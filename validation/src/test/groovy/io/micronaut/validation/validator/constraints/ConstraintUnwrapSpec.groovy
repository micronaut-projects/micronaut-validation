package io.micronaut.validation.validator.constraints


import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.context.ApplicationContext
import io.micronaut.validation.validator.Validator
import spock.lang.AutoCleanup
import spock.lang.Shared

class ConstraintUnwrapSpec extends AbstractTypeElementSpec {

    @Shared
    @AutoCleanup
    ApplicationContext context = ApplicationContext.run()

    @Shared
    Validator validator = context.getBean(Validator)


    void "test @NotNull(payload = Unwrapping.Skip.class) should be applied to the optional container"() {
        given:
        def introspection = buildBeanIntrospection('test.Test', """
package test;

import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.valueextraction.Unwrapping;
import java.util.Optional;

@io.micronaut.core.annotation.Introspected
class Test {
    @NotNull(payload = Unwrapping.Skip.class)
    private Optional<String> field;

    public Optional<String> getField() {
        return field;
    }

    public void setField(Optional<String> f) {
        this.field = f;
    }
}
""")
        def instance = introspection.instantiate()
        def prop = introspection.getProperty("field").get()
        prop.set(instance, null)
        def constraintViolations = validator.validate(introspection, instance)

        expect:
        constraintViolations.size() == 1
        constraintViolations.iterator().next().message == "must not be null"
    }

    void "test @NotNull(payload = Unwrapping.Skip.class) should be applied to the OptionalInt container"() {
        given:
        def introspection = buildBeanIntrospection('test.Test', """
package test;

import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.valueextraction.Unwrapping;
import java.util.OptionalInt;

@io.micronaut.core.annotation.Introspected
class Test {
    @NotNull(payload = Unwrapping.Skip.class)
    private OptionalInt field;

    public OptionalInt getField() {
        return field;
    }

    public void setField(OptionalInt f) {
        this.field = f;
    }
}
""")
        def instance = introspection.instantiate()
        def prop = introspection.getProperty("field").get()
        prop.set(instance, null)
        def constraintViolations = validator.validate(introspection, instance)

        expect:
        constraintViolations.size() == 1
        constraintViolations.iterator().next().message == "must not be null"
    }

    void "test @NotNull should be applied on the optional value"() {
        given:
        def introspection = buildBeanIntrospection('test.Test', """
package test;

import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.valueextraction.Unwrapping;
import java.util.Optional;

@io.micronaut.core.annotation.Introspected
class Test {
    @NotNull
    private Optional<String> field;

    public Optional<String> getField() {
        return field;
    }

    public void setField(Optional<String> f) {
        this.field = f;
    }
}
""")
        def instance = introspection.instantiate()
        def prop = introspection.getProperty("field").get()
        prop.set(instance, null)
        def constraintViolations = validator.validate(introspection, instance)

        expect:
        constraintViolations.size() == 1
        constraintViolations.iterator().next().message == "must not be null"
    }

    void "test @NotNull should be applied on the optional value 2"() {
        given:
        def introspection = buildBeanIntrospection('test.Test', """
package test;

import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.valueextraction.Unwrapping;
import java.util.Optional;

@io.micronaut.core.annotation.Introspected
class Test {
    @NotNull
    private Optional<String> field;

    public Optional<String> getField() {
        return field;
    }

    public void setField(Optional<String> f) {
        this.field = f;
    }
}
""")
        def instance = introspection.instantiate()
        def prop = introspection.getProperty("field").get()
        prop.set(instance, Optional.empty())
        def constraintViolations = validator.validate(introspection, instance)

        expect:
        constraintViolations.size() == 0
    }

    void "test @NotNull should be applied on the OptionalInt value"() {
        given:
        def introspection = buildBeanIntrospection('test.Test', """
package test;

import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.valueextraction.Unwrapping;
import java.util.OptionalInt;

@io.micronaut.core.annotation.Introspected
class Test {
    @NotNull
    private OptionalInt field;

    public OptionalInt getField() {
        return field;
    }

    public void setField(OptionalInt f) {
        this.field = f;
    }
}
""")
        def instance = introspection.instantiate()
        def prop = introspection.getProperty("field").get()
        prop.set(instance, null)
        def constraintViolations = validator.validate(introspection, instance)

        expect:
        constraintViolations.size() == 1
        constraintViolations.iterator().next().message == "must not be null"
    }

    void "test @NotNull(payload = Unwrapping.Skip.class) should be applied on the OptionalInt value"() {
        given:
        def introspection = buildBeanIntrospection('test.Test', """
package test;

import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.valueextraction.Unwrapping;
import java.util.OptionalInt;

@io.micronaut.core.annotation.Introspected
class Test {
    @NotNull(payload = Unwrapping.Skip.class)
    private OptionalInt field;

    public OptionalInt getField() {
        return field;
    }

    public void setField(OptionalInt f) {
        this.field = f;
    }
}
""")
        def instance = introspection.instantiate()
        def prop = introspection.getProperty("field").get()
        prop.set(instance, OptionalInt.empty())
        def constraintViolations = validator.validate(introspection, instance)

        expect:
        constraintViolations.size() == 0
    }

    void "test @NotNull should be applied on the OptionalInt value 2"() {
        given:
        def introspection = buildBeanIntrospection('test.Test', """
package test;

import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.valueextraction.Unwrapping;
import java.util.OptionalInt;

@io.micronaut.core.annotation.Introspected
class Test {
    @NotNull
    private OptionalInt field;

    public OptionalInt getField() {
        return field;
    }

    public void setField(OptionalInt f) {
        this.field = f;
    }
}
""")
        def instance = introspection.instantiate()
        def prop = introspection.getProperty("field").get()
        prop.set(instance, OptionalInt.empty())
        def constraintViolations = validator.validate(introspection, instance)

        expect:
        constraintViolations.size() == 1
        constraintViolations.iterator().next().message == "must not be null"
    }

}
