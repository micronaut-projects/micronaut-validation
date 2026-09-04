package io.micronaut.validation.validator.introspection

import io.micronaut.core.beans.BeanIntrospector
import io.micronaut.reflection.ReflectionBeanIntrospector
import io.micronaut.validation.validator.DefaultValidator
import io.micronaut.validation.validator.DefaultValidatorConfiguration
import jakarta.validation.ConstraintDeclarationException
import spock.lang.Shared
import spock.lang.Specification

/**
 * The validator over generated introspections supplemented by the reflection bridge, as the TCK runs it.
 */
class IntrospectionModeSpec extends Specification {

    @Shared
    DefaultValidator validator = new DefaultValidator(new DefaultValidatorConfiguration().tap {
        beanIntrospector = new ReflectionBeanIntrospector(BeanIntrospector.SHARED, type -> true, true, Set.of(io.micronaut.core.annotation.Introspected.AccessKind.FIELD, io.micronaut.core.annotation.Introspected.AccessKind.METHOD))
    })

    void "the constraints on the type arguments of a getter are validated"() {
        when:
        def violations = validator.validate(new Names())

        then:
        violations*.propertyPath*.toString() == ["strings[0]<list element>"]
    }

    void "the constraints on the type arguments of a getter are validated without a generated introspection"() {
        expect:
        validator.validate(new Holder.NotIntrospected())*.propertyPath*.toString() == ["strings[0]<list element>"]
        validator.validate(new Holder.NotIntrospectedField())*.propertyPath*.toString() == ["strings[0]<list element>"]
    }

    void "a group conversion on a return value that is not cascaded is a declaration error"() {
        when:
        validator.forExecutables().validateReturnValue(new Names(), Names.getMethod("retrieve"), [])

        then:
        thrown(ConstraintDeclarationException)
    }

    void "a cross-parameter constraint repeated in its container is validated for each occurrence"() {
        when:
        def violations = validator.forExecutables().validateParameters(new Names(), Names.getMethod("setNames", String, CharSequence), ["a", "b"] as Object[])

        then:
        violations*.message.sort() == ["first", "second"]
    }

    void "a group conversion on a container element of a field applies to the cascaded element"() {
        when:
        def violations = validator.validate(new User())

        then:
        violations*.propertyPath*.toString().sort() == ["addresses[0].zip", "nested[k][0]<list element>"]
    }

    void "nested container elements described by the bridge are validated at every depth"() {
        when:
        def violations = validator.validate(new Nested())

        then:
        violations.size() == 4
        violations*.propertyPath*.toString().every { it.contains("<list element>") || it.contains("<map value>") }
    }

    void "a constraint declared by an interface member is in the group of the interface for the types implementing it"() {
        expect: "the interface itself describes the constraint in the default group"
        validator.getConstraintsForClass(Named).getConstraintsForProperty("lastName").constraintDescriptors.first().groups == [jakarta.validation.groups.Default] as Set

        and: "an implementation describes it in the group of the interface as well"
        validator.getConstraintsForClass(NamedPerson).getConstraintsForProperty("lastName").constraintDescriptors.first().groups == [jakarta.validation.groups.Default, Named] as Set

        and: "validating the implementation in the group of the interface applies it"
        validator.validate(new NamedPerson(), Named)*.propertyPath*.toString() == ["lastName"]
    }

    void "the default group sequence a super type redefines applies to its own constraints only"() {
        when:
        def violations = validator.validate(new Child())

        then: "the parent stops at its first failing group, the child validates its default group"
        violations*.propertyPath*.toString().sort() == ["nickname", "size"]
    }
}
