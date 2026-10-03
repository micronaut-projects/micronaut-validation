package io.micronaut.validation.visitor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.inject.beans.visitor.IntrospectedTypeElementVisitor
import io.micronaut.inject.visitor.TypeElementVisitor
import io.micronaut.validation.validator.metadata.ValidationMetadataSupport

/**
 * The validation processor describes the types that take part in validation, and leaves the introspection of
 * any other type - a serialization DTO, an entity without constraints - as its author declared it.
 */
class IntrospectionScopeSpec extends AbstractTypeElementSpec {

    @Override
    protected Collection<TypeElementVisitor> getLocalTypeElementVisitors() {
        return [new ValidationVisitor(), new IntrospectedValidationIndexesVisitor(), new IntrospectedTypeElementVisitor()]
    }

    void "an introspection without constraints is left as declared"() {
        when:
        def introspection = buildBeanIntrospection('test.Plain', '''
package test;

import io.micronaut.core.annotation.Introspected;

@Introspected
class Plain {
    private String name;
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
''')

        then:
        !introspection.separatesDeclarations()
        !introspection.annotationMetadata.hasAnnotation(ValidationMetadataSupport.HIERARCHY)
    }

    void "a constrained introspection is described for validation"() {
        when:
        def introspection = buildBeanIntrospection('test.Constrained', '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;

@Introspected
class Constrained {
    @NotBlank
    private String name;
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
''')

        then:
        introspection.separatesDeclarations()
        introspection.annotationMetadata.hasAnnotation(ValidationMetadataSupport.HIERARCHY)
    }

    void "a type inheriting constraints is described for validation"() {
        when:
        def introspection = buildBeanIntrospection('test.Child', '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;

class Base {
    @NotBlank
    public String getName() { return "x"; }
}

@Introspected
class Child extends Base {
}
''')

        then:
        introspection.annotationMetadata.hasAnnotation(ValidationMetadataSupport.HIERARCHY)
    }
}
