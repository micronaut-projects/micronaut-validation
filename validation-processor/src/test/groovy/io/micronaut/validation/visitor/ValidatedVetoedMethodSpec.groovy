package io.micronaut.validation.visitor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.core.annotation.Vetoed
import io.micronaut.inject.ast.ClassElement
import io.micronaut.inject.beans.visitor.IntrospectedTypeElementVisitor
import io.micronaut.inject.visitor.TypeElementVisitor
import io.micronaut.inject.visitor.VisitorContext
import jakarta.validation.constraints.NotBlank

/**
 * A vetoed method is not validated when it is invoked, and is described all the same: the metadata API reads
 * a MethodDescriptor from a bean method of the introspection.
 */
class ValidatedVetoedMethodSpec extends AbstractTypeElementSpec {

    final static String VALIDATED_ANN = "io.micronaut.validation.Validated"

    @Override
    protected Collection<TypeElementVisitor> getLocalTypeElementVisitors() {
        return [new VetoingVisitor(), new ValidationVisitor(), new IntrospectedTypeElementVisitor()]
    }

    void "test a vetoed constrained method is still described"() {
        given:
        def introspection = buildBeanIntrospection('test.Test', '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;

@Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD},
              visibility = Introspected.Visibility.ANY)
class Test {

    public void vetoedSetName(@NotBlank String name) {
    }

    public void setName(@NotBlank String name) {
    }
}
''')

        when:
        def vetoed = introspection.beanMethods.find { it.name == 'vetoedSetName' }
        def validated = introspection.beanMethods.find { it.name == 'setName' }

        then:
        vetoed != null
        vetoed.arguments[0].annotationMetadata.hasAnnotation(NotBlank)
        validated != null
    }

    void "test a vetoed constrained method is not validated on invocation"() {
        given:
        def definition = buildBeanDefinition('test.Test', '''
package test;

import jakarta.validation.constraints.NotBlank;

@jakarta.inject.Singleton
class Test {

    public void vetoedSetName(@NotBlank String name) {
    }

    public void setName(@NotBlank String name) {
    }
}
''')

        expect:
        !definition.findMethod("vetoedSetName", String).get().hasStereotype(VALIDATED_ANN)
        definition.findMethod("setName", String).get().hasStereotype(VALIDATED_ANN)
    }

    /**
     * Stands in for a visitor that vetoes a method, the way the Jakarta Validation TCK harness does for a
     * method {@code @ValidateOnExecution} turns off.
     */
    static class VetoingVisitor implements TypeElementVisitor<Object, Object> {

        @Override
        VisitorKind getVisitorKind() {
            return VisitorKind.ISOLATING
        }

        @Override
        int getOrder() {
            return 88 // ahead of ValidationVisitor, the way the TCK harness visitor is
        }

        @Override
        void visitClass(ClassElement element, VisitorContext context) {
            element.getMethods().findAll { it.name.startsWith('vetoed') }.each { it.annotate(Vetoed) }
        }
    }
}
