package io.micronaut.validation.validator.constraints.custom

import io.micronaut.context.ApplicationContext
import io.micronaut.core.annotation.Introspected
import io.micronaut.validation.validator.Validator
import jakarta.validation.Valid
import jakarta.validation.ValidatorFactory
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class CustomConstraintsSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext applicationContext = ApplicationContext.run()
    @Shared
    Validator validator = applicationContext.getBean(Validator)
    @Shared
    ValidatorFactory validatorFactory = applicationContext.getBean(ValidatorFactory)

    void "test validation where pojo with inner custom constraint fails"() {
        given:
        TestInvalid testInvalid = new TestInvalid(invalidInner: new TestInvalid.InvalidInner())

        when:
        def violations = validator.validate(testInvalid)

        then:
        violations.size() == 1
        violations[0].message == "invalid"
    }

    void "test validation where pojo with outer custom constraint fails"() {
        given:
        TestInvalid testInvalid = new TestInvalid(invalidOuter: new InvalidOuter())

        when:
        def violations = validator.validate(testInvalid)

        then:
        violations.size() == 1
        violations[0].message == "invalid"
    }

    void "test validation where pojo with inner and outer custom constraint both fail"() {
        given:
        TestInvalid testInvalid = new TestInvalid(
                invalidInner: new TestInvalid.InvalidInner(),
                invalidOuter: new InvalidOuter())

        when:
        def violations = validator.validate(testInvalid)

        then:
        violations.size() == 2
        violations[0].message == "invalid"
        violations[1].message == "invalid"
    }

    void "test validation where inner custom constraint fails"() {
        given:
        TestInvalid.InvalidInner invalidInner = new TestInvalid.InvalidInner()

        when:
        def violations = validator.validate(invalidInner)

        then:
        violations.size() == 1
        violations[0].message == "invalid"
    }

    void "test validation where outer custom constraint fails"() {
        given:
        InvalidOuter invalidOuter = new InvalidOuter()

        when:
        def violations = validator.validate(invalidOuter)

        then:
        violations.size() == 1
        violations[0].message == "invalid"
    }

    void "test validation where pojo with inner custom message constraint fails"() {
        given:
        CustomTestInvalid testInvalid = new CustomTestInvalid(invalidInner: new CustomTestInvalid.CustomInvalidInner())

        when:
        def violations = validator.validate(testInvalid)

        then:
        violations.size() == 1
        violations[0].message == "custom invalid"
    }

    void "test validation where pojo with outer custom message constraint fails"() {
        given:
        CustomTestInvalid testInvalid = new CustomTestInvalid(invalidOuter: new CustomInvalidOuter())

        when:
        def violations = validator.validate(testInvalid)

        then:
        violations.size() == 1
        violations[0].message == "custom invalid"
    }

    void "test validation where pojo with inner and outer custom message constraint both fail"() {
        given:
        CustomTestInvalid testInvalid = new CustomTestInvalid(
                invalidInner: new CustomTestInvalid.CustomInvalidInner(),
                invalidOuter: new CustomInvalidOuter())

        when:
        def violations = validator.validate(testInvalid)

        then:
        violations.size() == 2
        violations[0].message == "custom invalid"
        violations[1].message == "custom invalid"
    }

    void "test validation where inner custom message constraint fails"() {
        given:
        CustomTestInvalid.CustomInvalidInner invalidInner = new CustomTestInvalid.CustomInvalidInner()

        when:
        def violations = validator.validate(invalidInner)

        then:
        violations.size() == 1
        violations[0].message == "custom invalid"
    }

    void "test validation where outer custom message constraint fails"() {
        given:
        CustomInvalidOuter invalidOuter = new CustomInvalidOuter()

        when:
        def violations = validator.validate(invalidOuter)

        then:
        violations.size() == 1
        violations[0].message == "custom invalid"
    }

    void "test validation bean where outer custom message constraint fails"() {
        given:
        CustomInvalidOuter2 invalidOuter = new CustomInvalidOuter2()

        when:
        def violations = validator.validate(invalidOuter)

        then:
        violations.size() == 1
        violations[0].message == "custom invalid"
    }

    void "test intercepted validation if BeanWithConstraintAndPrivateMethods"() {
        given:
        BeanWithConstraintAndPrivateMethods abean = new BeanWithConstraintAndPrivateMethods()

        when:
        def violations = validator.validate(abean)

        then:
        violations.size() == 1
        violations[0].message == "custom invalid"
    }

    void "test bean validation if BeanWithConstraintAndPrivateMethods"() {
        given:
        BeanWithConstraintAndPrivateMethods abean = applicationContext.getBean(BeanWithConstraintAndPrivateMethods)

        when:
        def result = abean.publicCombine1("A", "B")

        then:
        result == "AB"

        when:
        result = abean.publicCombine2("A", "B")

        then:
        result == "AB"

        when:
        result = abean.publicCombine3("A", "B")

        then:
        result == "AB"

        when:
        result = abean.publicCombine4("A", "B")

        then:
        result == "AB"
    }

    void "test custom validator bean can be invoked when using jakarta validation ValidatorFactory.usingContext"() {
        given:
        TestInvalid testInvalid = new TestInvalid(invalidInner: new TestInvalid.InvalidInner())
        Validator contextualValidator = validatorFactory.usingContext().getValidator() as Validator

        when:
        def violations = contextualValidator.validate(testInvalid)

        then:
        violations.size() == 1
        violations[0].message == "invalid"
    }
}

@Introspected
class TestInvalid {
    @Valid
    InvalidInner invalidInner

    @Valid
    InvalidOuter invalidOuter

    @Introspected
    @AlwaysInvalidConstraint
    static class InvalidInner {}
}

@Introspected
@AlwaysInvalidConstraint
class InvalidOuter {}

@Introspected
class CustomTestInvalid {
    @Valid
    CustomInvalidInner invalidInner

    @Valid
    CustomInvalidOuter invalidOuter

    @Introspected
    @CustomMessageConstraint
    static class CustomInvalidInner {}
}

@Introspected
@CustomMessageConstraint
class CustomInvalidOuter {}

@Introspected
@CustomMessageConstraint2
class CustomInvalidOuter2 {}
