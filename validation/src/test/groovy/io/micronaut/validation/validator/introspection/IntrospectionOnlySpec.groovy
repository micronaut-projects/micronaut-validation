package io.micronaut.validation.validator.introspection

import io.micronaut.core.annotation.Introspected
import io.micronaut.core.beans.BeanIntrospector
import io.micronaut.validation.validator.DefaultValidator
import io.micronaut.validation.validator.DefaultValidatorConfiguration
import jakarta.validation.ConstraintDeclarationException
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import spock.lang.Shared
import spock.lang.Specification

/**
 * The validator over the generated introspections only, the way the jakartaTckIntrospection task runs it.
 */
class IntrospectionOnlySpec extends Specification {

    @Shared
    DefaultValidator validator = new DefaultValidator(new DefaultValidatorConfiguration().tap {
        beanIntrospector = BeanIntrospector.SHARED
    })

    void "the constraints an interface and its implementation declare on a property add up"() {
        given:
        def descriptor = validator.getConstraintsForClass(Impl)

        expect:
        descriptor.getConstraintsForProperty("name") != null
        descriptor.getConstraintsForProperty("name").constraintDescriptors*.annotation*.annotationType() as Set ==
            [DecimalMin, Size] as Set
    }

    void "the constraints a super class and its sub class declare on a property add up"() {
        given:
        def descriptor = validator.getConstraintsForClass(Impl)

        expect:
        descriptor.getConstraintsForProperty("lastName").constraintDescriptors*.annotation*.annotationType() as Set ==
            [DecimalMin, Size] as Set
    }

    void "a constrained method of an introspected type has a descriptor"() {
        given:
        def descriptor = validator.getConstraintsForClass(Parallel)
            .getConstraintsForMethod("rename", String)

        expect:
        descriptor != null
        descriptor.parameterDescriptors[0].constraintDescriptors*.annotation*.annotationType() == [NotNull]
    }

    void "a parameter constrained in parallel interfaces is a declaration error"() {
        when:
        validator.forExecutables().validateParameters(new Parallel(), Parallel.getMethod("place", String), ["a"] as Object[])

        then:
        thrown(ConstraintDeclarationException)
    }

    void "the constraints an interface declares on a property are in the implicit group of the interface"() {
        given:
        def descriptor = validator.getConstraintsForClass(Impl).getConstraintsForProperty("name")

        expect:
        descriptor.findConstraints().unorderedAndMatchingGroups(Contract).constraintDescriptors*.annotation*.annotationType() == [Size]
    }

    @Introspected(accessKind = [Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD], visibility = Introspected.Visibility.ANY)
    static interface Contract {
        @Size(min = 5)
        String getName()
    }

    @Introspected(accessKind = [Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD], visibility = Introspected.Visibility.ANY)
    static class Base {
        @NotNull
        private String base

        String getBase() { base }

        @Size(min = 5)
        String getLastName() { null }
    }

    @Introspected(accessKind = [Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD], visibility = Introspected.Visibility.ANY)
    static class Impl extends Base implements Contract {
        @Override
        @DecimalMin("10")
        String getName() { "3" }

        @Override
        @DecimalMin("10")
        String getLastName() { "3" }
    }

    @Introspected(accessKind = [Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD], visibility = Introspected.Visibility.ANY)
    static interface LeftOrder {
        void place(@NotNull String name)
    }

    @Introspected(accessKind = [Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD], visibility = Introspected.Visibility.ANY)
    static interface RightOrder {
        void place(@Size(min = 5) String name)
    }

    @Introspected(accessKind = [Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD], visibility = Introspected.Visibility.ANY)
    static class Parallel implements LeftOrder, RightOrder {
        @Override
        void place(String name) {
        }

        void rename(@NotNull String name) {
        }
    }
}
