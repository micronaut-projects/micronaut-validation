package io.micronaut.validation.visitor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.validation.annotation.ValidatedElement
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

class ValidatedTypeArgumentInheritanceSpec extends AbstractTypeElementSpec {

    final static String VALIDATED_ANN = "io.micronaut.validation.Validated"

    void "test constraints inherit for generic parameters"() {
        given:
        def definition = buildBeanDefinition('test.Test','''
package test;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@jakarta.inject.Singleton
class Test implements TestBase {
    @Override
    public void setList(List<List<String>> list) {
    }
}

interface TestBase {
    @io.micronaut.context.annotation.Executable
    void setList(List<@NotNull List<@Pattern(regexp = "[a-zA-Z]+") @Size(min = 3) @NotNull String>> list);
}
''')
        when:
        def method = definition.getRequiredMethod("setList", List<List<String>>)

        then:
        method.hasStereotype(VALIDATED_ANN)
        method.arguments.size() == 1
        method.arguments[0].annotationMetadata.hasAnnotation(ValidatedElement)
        method.arguments[0].typeParameters.size() == 1

        def firstTypeParamAnnMetadataAnnMetadata = method.arguments[0].typeParameters[0].annotationMetadata

        firstTypeParamAnnMetadataAnnMetadata.hasAnnotation(NotNull)
        firstTypeParamAnnMetadataAnnMetadata.hasAnnotation(ValidatedElement)
        method.arguments[0].typeParameters[0].typeParameters.size() == 1

        def secTypeParamAnnMetadata = method.arguments[0].typeParameters[0].typeParameters[0].annotationMetadata

        secTypeParamAnnMetadata.hasAnnotation(NotNull)
        secTypeParamAnnMetadata.hasAnnotation(Size)
        secTypeParamAnnMetadata.getAnnotation(Size).intValue("min").orElse(-1) == 3
        secTypeParamAnnMetadata.hasAnnotation(Pattern)
        secTypeParamAnnMetadata.getAnnotation(Pattern).stringValue("regexp").orElse(null) == "[a-zA-Z]+"
    }

    void "test constraints inherit for generic parameters of return type"() {
        given:
        def definition = buildBeanDefinition('test.Test','''
package test;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@jakarta.inject.Singleton
class Test implements TestBase {
    @Override
    public List<List<String>> getList() {
        return null;
    }
}

interface TestBase {
    @io.micronaut.context.annotation.Executable
    List<@NotNull List<@Pattern(regexp = "[a-zA-Z]+") @Size(min = 3) @NotNull String>> getList();
}
''')
        when:
        def method = definition.getRequiredMethod("getList")

        then:
        method.hasStereotype(VALIDATED_ANN)
        method.returnType.annotationMetadata.hasAnnotation("io.micronaut.validation.annotation.ValidatedElement")
        method.returnType.typeParameters.size() == 1
        def firstTypeParamAnnMetadataAnnMetadata = method.returnType.typeParameters[0].annotationMetadata

        firstTypeParamAnnMetadataAnnMetadata.hasAnnotation(NotNull)
        firstTypeParamAnnMetadataAnnMetadata.hasAnnotation(ValidatedElement)
        method.returnType.typeParameters[0].typeParameters.size() == 1

        def secTypeParamAnnMetadata = method.returnType.typeParameters[0].typeParameters[0].annotationMetadata

        secTypeParamAnnMetadata.hasAnnotation(NotNull)
        secTypeParamAnnMetadata.hasAnnotation(Size)
        secTypeParamAnnMetadata.getAnnotation(Size).intValue("min").orElse(-1) == 3
        secTypeParamAnnMetadata.hasAnnotation(Pattern)
        secTypeParamAnnMetadata.getAnnotation(Pattern).stringValue("regexp").orElse(null) == "[a-zA-Z]+"
    }

    void "test constraints inherit for deep generic parameters"() {
        given:
        def definition = buildBeanDefinition('test.Test','''
package test;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@jakarta.inject.Singleton
class Test implements TestBase {
    @Override
    public void map(Map<@NotBlank String, List<@NotNull List<@NotBlank String>>> list) {
    }
}

interface TestBase {
    @io.micronaut.context.annotation.Executable
    void map(Map<String, List<@NotNull List<@Pattern(regexp = "[a-zA-Z]+") @Size(min = 3) @NotNull String>>> list);
}
''')
        when:
        def method = definition.getRequiredMethod("map", Map<String, List<String>>)

        then:
        method.hasStereotype(VALIDATED_ANN)
        method.arguments.size() == 1
        method.arguments[0].annotationMetadata.hasAnnotation("io.micronaut.validation.annotation.ValidatedElement")
        method.arguments[0].typeParameters.size() == 2
        method.arguments[0].typeParameters[0].annotationMetadata.hasAnnotation(NotBlank)
        method.arguments[0].typeParameters[0].annotationMetadata.hasAnnotation(ValidatedElement)
        method.arguments[0].typeParameters[1].annotationMetadata.hasAnnotation(ValidatedElement)
        method.arguments[0].typeParameters[1].typeParameters.length == 1
        method.arguments[0].typeParameters[1].typeParameters[0].annotationMetadata.hasAnnotation(ValidatedElement)
        method.arguments[0].typeParameters[1].typeParameters[0].annotationMetadata.hasAnnotation(NotNull)

        method.arguments[0].typeParameters[1].typeParameters[0].typeParameters.length == 1
        method.arguments[0].typeParameters[1].typeParameters[0].typeParameters[0].annotationMetadata.hasAnnotation(ValidatedElement)
        method.arguments[0].typeParameters[1].typeParameters[0].typeParameters[0].annotationMetadata.hasAnnotation(Pattern)
        method.arguments[0].typeParameters[1].typeParameters[0].typeParameters[0].annotationMetadata.hasAnnotation(NotNull)
        method.arguments[0].typeParameters[1].typeParameters[0].typeParameters[0].annotationMetadata.hasAnnotation(Size)
        method.arguments[0].typeParameters[1].typeParameters[0].typeParameters[0].annotationMetadata.hasAnnotation(NotBlank)
    }

    void "test constraints inherit for generic parameters from abstract class"() {
        given:
        def definition = buildBeanDefinition('test.Test','''
package test;

import java.util.*;
import jakarta.validation.constraints.Size;

@jakarta.inject.Singleton
class Test extends AbstractTest {
    @Override
    public void map(Map<String, @Size(min=2) String> value) {
    }
}

abstract class AbstractTest {
    void map(Map<String, @Size(min=2) String> value) {

    }
}
''')
        when:
        def method = definition.getRequiredMethod("map", Map<String, List<String>>)

        then:
        method.hasStereotype(VALIDATED_ANN)
        method.arguments.size() == 1
        method.arguments[0].annotationMetadata.hasAnnotation("io.micronaut.validation.annotation.ValidatedElement")
        method.arguments[0].typeParameters.size() == 2
        var anns = method.arguments[0].typeParameters[1].annotationMetadata.getAnnotationValuesByType(Size)
        anns.size() == 1
        anns.get(0).intValue("min").get() == 2
    }

}
