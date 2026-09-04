package io.micronaut.validation.retained

import io.micronaut.core.annotation.AnnotationValue
import io.micronaut.core.beans.BeanIntrospector
import io.micronaut.reflection.ReflectionAnnotations
import jakarta.validation.Constraint
import jakarta.validation.constraints.Size
import spock.lang.Specification

/**
 * The retained tree the processors record for a constraint and the tree {@code ReflectionAnnotations} builds
 * for the same element, compared.
 *
 * <p>The constraint contract is not annotated {@code @Retainable} in its source - the validation processor
 * marks it through a remapper, which does not run at runtime - and an override is declared as
 * {@code @OverridesAttribute}, which a transformer maps onto {@code @AliasFor} at compilation time. The
 * customizer states both for the metadata built reflectively, and this is what says the two agree: describing
 * a composed constraint reads the tree, so a type the processors never saw would otherwise lose the
 * constraints it composes.</p>
 */
class ReflectiveTreeParitySpec extends Specification {

    private static List<String> describe(AnnotationValue<?> composed) {
        return composed.getStereotypes().collect {
            it.getAnnotationName() + it.getValues().findAll { k, v -> k.toString() != '$stereotypes' }
        }
    }

    void "the tree built reflectively is the tree the processors record"() {
        given:
        def compileTime = BeanIntrospector.SHARED.getIntrospection(Account)
            .getRequiredProperty("username", String).getAnnotationMetadata()
        def reflective = ReflectionAnnotations.metadataOf(Account.getDeclaredField("username"))

        expect:
        describe(reflective.getAnnotation(MinimumLength)) == describe(compileTime.getAnnotation(MinimumLength))
    }

    void "the constraint contract is retainable at runtime, so a composed constraint keeps what it composes"() {
        when:
        def retained = ReflectionAnnotations.metadataOf(Account.getDeclaredField("username"))
            .getAnnotation(MinimumLength).getStereotypes()

        then: "the contract and the constraint composed are both retained"
        retained*.getAnnotationName() == [Constraint.name, Size.name]

        and: "the composed occurrence carries the member @OverridesAttribute overrides, not the meta-annotated 5"
        retained.find { it.getAnnotationName() == Size.name }.intValue("min").getAsInt() == 8
    }

}
