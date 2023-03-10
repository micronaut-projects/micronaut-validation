package io.micronaut.validation

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.inject.BeanDefinition
import io.micronaut.inject.ValidatedBeanDefinition
import io.micronaut.inject.writer.BeanDefinitionVisitor
import jakarta.validation.Valid

import java.time.LocalDate

class ValidatedParseSpec extends AbstractTypeElementSpec {
    final static String VALIDATED_ANN = "io.micronaut.validation.Validated";

    void "test constraints on beans make them @Validated"() {
        given:
        def definition = buildBeanDefinition('test.Test','''
package test;

@jakarta.inject.Singleton
class Test {

    @io.micronaut.context.annotation.Executable
    public void setName(@jakarta.validation.constraints.NotBlank String name) {

    }

    @io.micronaut.context.annotation.Executable
    public void setName2(@jakarta.validation.Valid String name) {

    }
}
''')

        expect:
        definition != null
        definition.findMethod("setName", String).get().hasStereotype(VALIDATED_ANN)
    }

    void "test constraints on a declarative client makes it @Validated"() {
        given:
        def definition = buildBeanDefinition('test.ExchangeRates' + BeanDefinitionVisitor.PROXY_SUFFIX,'''
package test;

import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.annotation.Client;

import jakarta.validation.constraints.PastOrPresent;
import java.time.LocalDate;

@Client("https://exchangeratesapi.io")
interface ExchangeRates {

    @Get("{date}")
    String rate(@PastOrPresent LocalDate date);
}
''')

        expect:
        definition.findMethod("rate", LocalDate).get().hasStereotype(VALIDATED_ANN)
    }

    void "test constraints on generic parameters make method @Validated"() {
        given:
        def definition = buildBeanDefinition('test.Test','''
package test;

import java.util.List;
import jakarta.validation.constraints.NotBlank;

@jakarta.inject.Singleton
class Test {
    @io.micronaut.context.annotation.Executable
    public void setList(List<@NotBlank String> list) {

    }
}
''')
        when:
        def method = definition.getRequiredMethod("setList", List<String>);

        then:
        method.hasStereotype(VALIDATED_ANN)
        method.arguments.size() == 1
        method.arguments[0].annotationMetadata.hasAnnotation(Valid)
    }

    void "test constraints on a controller operation make method @Validated"() {
        given:
        def definition = buildBeanDefinition('test.Test', '''
package test;

import java.util.List;
import jakarta.validation.Valid;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;

@Controller()
class Test {
    @Post("/pojos")
    public List<Pojo> pojos(@Body List<@Valid Pojo> pojos) {
        return pojos;
    }

    @Introspected
    public record Pojo() {}
}
''')
        var method = definition.findPossibleMethods("pojos").findFirst()

        expect:
        method.isPresent()
        method.get().hasStereotype(VALIDATED_ANN)
    }

    void "test constraints on return value generic parameters make method @Validated"() {
        given:
        def definition = buildBeanDefinition('test.Test','''
package test;

import java.util.List;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import io.micronaut.context.annotation.Executable;

@jakarta.inject.Singleton
class Test {

    @Executable
    public @Min(value=10) Integer getValue() {
        return 1;
    }

    @Executable
    public List<@NotNull String> getStrings() {
        return null;
    }
}
''')
        var method = definition.findMethod("getValue")

        expect:
        method.isPresent()
        method.get().hasStereotype(VALIDATED_ANN)

        when:
        var method2 = definition.findMethod("getStrings")

        then:
        method2.isPresent()
        method2.get().hasStereotype(VALIDATED_ANN)
    }

    void "test constraints on reactive return value make method @Validated"() {
        given:
        def definition = buildBeanDefinition('test.Test','''
package test;

import jakarta.validation.constraints.NotBlank;
import io.micronaut.context.annotation.Executable;
import reactor.core.publisher.Mono;

@jakarta.inject.Singleton
class Test {

    @Executable
    public Mono<@NotBlank String> getMono() {
        return Mono.fromCallable(() -> "");
    }
}
''')
        var method = definition.findMethod("getMono")

        expect:
        method.isPresent()
        method.get().hasStereotype(VALIDATED_ANN)
    }

    void "test recursive generic type parameter doesn't result in StackOverflow"() {
        given:
        BeanDefinition definition = buildBeanDefinition('test.TrackedSortedSet', '''\
package test;

import io.micronaut.inject.annotation.*;
import io.micronaut.context.annotation.*;

@jakarta.inject.Singleton
final class TrackedSortedSet<T extends java.lang.Comparable<? super T>> {
 public TrackedSortedSet(java.util.Collection<? extends T> initial) {
        super();
    }
}

''')
        expect:
        definition != null
    }

    void "test constraints on primitive value make method @Validated"() {
        given:
        def definition = buildBeanDefinition('test.Test','''
package test;

import jakarta.validation.constraints.NotBlank;
import io.micronaut.context.annotation.Executable;
import jakarta.validation.constraints.Min;

@jakarta.inject.Singleton
class Test {

    @Executable
    @Min(0)
    public int getInt() { return 0; }

    @Executable
    public void setInt(@Min(0) int num) {}
}
''')
        var getter = definition.findMethod("getInt")
        var setter = definition.findMethod("setInt", int.class)

        expect:
        getter.isPresent()
        getter.get().hasStereotype(VALIDATED_ANN)

        setter.isPresent()
        setter.get().hasStereotype(VALIDATED_ANN)
    }
}
