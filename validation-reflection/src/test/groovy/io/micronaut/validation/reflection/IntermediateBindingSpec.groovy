package io.micronaut.validation.reflection

import jakarta.validation.ConstraintDefinitionException
import jakarta.validation.ConstraintValidator
import spock.lang.Specification

/**
 * Type arguments bound at an intermediate generic super type, read by the walks of the reflection module, which
 * carry the bindings of every level in between rather than matching the super type by its raw type.
 */
class IntermediateBindingSpec extends Specification {

    void "the generic walk carries a binding made one level up"() {
        expect:
        ReflectionGenericArguments.resolveGenericToArgument(IntermediateBindings.StringCrossParameterValidator, ConstraintValidator)
            .typeParameters*.type == [IntermediateBindings.IllegalCrossParameter, String]
    }

    void "a cross-parameter validator validating String through an intermediate base is an illegal definition"() {
        when:
        ReflectedConstraintDefinitions.validate(IntermediateBindings.IllegalCrossParameter)

        then:
        def e = thrown(ConstraintDefinitionException)
        e.message.contains("Cross-parameter validator must validate Object or Object[]")
    }

    void "a cross-parameter validator validating Object[] through an intermediate base is a legal definition"() {
        when:
        ReflectedConstraintDefinitions.validate(IntermediateBindings.LegalCrossParameter)

        then:
        noExceptionThrown()
    }

    void "the type argument a container passes on is found through a level that swaps them"() {
        expect: "Map's value argument is Swapped's X, which Concrete binds to its argument 0"
        ReflectionContainerTypeArguments.extractedTypeArgumentIndex(IntermediateBindings.Concrete, Map, 1) == 0
        ReflectionContainerTypeArguments.extractedTypeArgumentIndex(IntermediateBindings.Concrete, Map, 0) == 1
    }

    void "a container swapping its arguments directly"() {
        expect:
        ReflectionContainerTypeArguments.extractedTypeArgumentIndex(IntermediateBindings.DirectlySwapped, Map, 1) == 0
        ReflectionContainerTypeArguments.extractedTypeArgumentIndex(IntermediateBindings.DirectlySwapped, Map, 0) == 1
    }

    void "a container binding its element to a type keeps the extractor's index"() {
        expect:
        ReflectionContainerTypeArguments.extractedTypeArgumentIndex(IntermediateBindings.Strings, Iterable, 0) == 0
    }

    void "a variable of an intermediate type sharing a name with one of the type's own is not mistaken for it"() {
        expect: "List passes E through Collection to Iterable, and E is the list's argument 0"
        ReflectionGenericArguments.declaredTypeArgumentIndex(ArrayList, Iterable, 0) == 0

        and: "a type not read as the container answers nothing"
        ReflectionGenericArguments.declaredTypeArgumentIndex(String, Iterable, 0) == null
    }
}
