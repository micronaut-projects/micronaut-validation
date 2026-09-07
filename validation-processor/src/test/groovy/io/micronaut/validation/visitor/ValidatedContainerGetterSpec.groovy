package io.micronaut.validation.visitor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern

/**
 * A getter that holds the value of a field in a container reads as one property of the container type, and
 * the constraints the field declares are declared for the type argument that holds the value.
 */
class ValidatedContainerGetterSpec extends AbstractTypeElementSpec {

    void "test the constraints of a field are declared for the type argument an Optional getter holds it in"() {
        given:
        def introspection = buildBeanIntrospection('test.Test', '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Pattern;
import java.util.Optional;

@Introspected
class Test {

    @Pattern(regexp = "[a-z]+")
    private String alpha;

    public Optional<String> getAlpha() {
        return Optional.ofNullable(alpha);
    }

    public void setAlpha(String alpha) {
        this.alpha = alpha;
    }
}
''')

        when:
        def argument = introspection.getRequiredProperty("alpha", Optional).asArgument()

        then:
        argument.type == Optional
        argument.typeParameters.length == 1
        argument.typeParameters[0].type == String
        argument.typeParameters[0].annotationMetadata.hasAnnotation(Pattern)
        argument.typeParameters[0].annotationMetadata.stringValue(Pattern, "regexp").get() == "[a-z]+"
    }

    void "test a type argument keeps the constraint it declares itself"() {
        given:
        def introspection = buildBeanIntrospection('test.Test', '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Optional;

@Introspected
class Test {

    @Pattern(regexp = "[a-z]+")
    private String alpha;

    public Optional<@NotNull String> getAlpha() {
        return Optional.ofNullable(alpha);
    }
}
''')

        when:
        def typeArgument = introspection.getRequiredProperty("alpha", Optional).asArgument().typeParameters[0]

        then:
        typeArgument.annotationMetadata.hasAnnotation(NotNull)
        typeArgument.annotationMetadata.hasAnnotation(Pattern)
    }

    void "test the constraints of a field are left alone when the getter holds many values"() {
        given:
        def introspection = buildBeanIntrospection('test.Test', '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Pattern;
import java.util.List;

@Introspected
class Test {

    @Pattern(regexp = "[a-z]+")
    private String alpha;

    public List<String> getAlpha() {
        return List.of(alpha);
    }
}
''')

        when:
        def typeArgument = introspection.getRequiredProperty("alpha", List).asArgument().typeParameters[0]

        then:
        !typeArgument.annotationMetadata.hasAnnotation(Pattern)
    }

    void "test a field and a getter of the same type keep the constraints on the property"() {
        given:
        def introspection = buildBeanIntrospection('test.Test', '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Pattern;

@Introspected
class Test {

    @Pattern(regexp = "[a-z]+")
    private String alpha;

    public String getAlpha() {
        return alpha;
    }
}
''')

        when:
        def property = introspection.getRequiredProperty("alpha", String)

        then:
        property.asArgument().type == String
        property.annotationMetadata.hasAnnotation(Pattern)
    }
}
