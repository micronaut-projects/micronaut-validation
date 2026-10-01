package io.micronaut.validation.visitor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.validation.annotation.ValidatedElement
import io.micronaut.core.type.Argument
import io.micronaut.validation.validator.metadata.GeneratedAnnotationFactories
import io.micronaut.validation.validator.metadata.GeneratedAnnotationProvider
import io.micronaut.validation.validator.ExecutableHierarchy
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
        def executable = definition.getRequiredMethod("setList", List<List<String>>)
        def method = hierarchy(definition, executable)

        then:
        executable.hasStereotype(VALIDATED_ANN)
        method.arguments.size() == 1
        method.arguments[0].typeParameters.size() == 1

        def firstTypeParamAnnMetadataAnnMetadata = method.arguments[0].typeParameters[0].annotationMetadata

        firstTypeParamAnnMetadataAnnMetadata.hasAnnotation(NotNull)
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
        def executable = definition.getRequiredMethod("getList")
        def method = hierarchy(definition, executable)

        then:
        executable.hasStereotype(VALIDATED_ANN)
        method.returnArgument.typeParameters.size() == 1
        def firstTypeParamAnnMetadataAnnMetadata = method.returnArgument.typeParameters[0].annotationMetadata

        firstTypeParamAnnMetadataAnnMetadata.hasAnnotation(NotNull)
        method.returnArgument.typeParameters[0].typeParameters.size() == 1

        def secTypeParamAnnMetadata = method.returnArgument.typeParameters[0].typeParameters[0].annotationMetadata

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
        def executable = definition.getRequiredMethod("map", Map<String, List<String>>)
        def method = hierarchy(definition, executable)

        then:
        executable.hasStereotype(VALIDATED_ANN)
        method.arguments.size() == 1
        method.arguments[0].typeParameters.size() == 2
        method.arguments[0].typeParameters[0].annotationMetadata.hasAnnotation(NotBlank)
        method.arguments[0].typeParameters[1].typeParameters.length == 1
        method.arguments[0].typeParameters[1].typeParameters[0].annotationMetadata.hasAnnotation(NotNull)

        method.arguments[0].typeParameters[1].typeParameters[0].typeParameters.length == 1
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
        def executable = definition.getRequiredMethod("map", Map<String, List<String>>)
        def method = hierarchy(definition, executable)

        then:
        executable.hasStereotype(VALIDATED_ANN)
        method.arguments.size() == 1
        method.arguments[0].typeParameters.size() == 2
        var anns = method.arguments[0].typeParameters[1].annotationMetadata.getAnnotationValuesByType(Size)
        anns.size() == 1
        anns.get(0).intValue("min").get() == 2
    }

    private static ExecutableHierarchy.Resolved hierarchy(definition, executable) {
        // The in-memory compiler harness does not expose generated service resources.
        // Use the provider embedded in the definition and instantiate the generated parent
        // provider directly; reflection here belongs to the test harness only.
        GeneratedAnnotationProvider own = GeneratedAnnotationFactories.embedded(definition.annotationMetadata)
        def types = own.typeMetadata(definition.beanType.name)
        Class<?> parent = types.interfaces().isEmpty() ? types.superType() : types.interfaces().first()
        GeneratedAnnotationProvider inherited = definition.beanType.classLoader
            .loadClass(parent.name + '$ValidationAnnotations').getConstructor().newInstance()
        Class<?>[] signature = Argument.toClassArray(executable.arguments)
        def local = own.methodDeclaration(executable.methodName, signature)
        def base = inherited.methodDeclaration(executable.methodName, signature)
        return ExecutableHierarchy.merge(ExecutableHierarchy.Declaration.of(executable),
            new ExecutableHierarchy.Declaration(local.declaringType(), local.metadata(),
                local.parameters().toArray(Argument[]::new), local.argument(), true),
            [new ExecutableHierarchy.Declaration(base.declaringType(), base.metadata(),
                base.parameters().toArray(Argument[]::new), base.argument(), true)])
    }

}
