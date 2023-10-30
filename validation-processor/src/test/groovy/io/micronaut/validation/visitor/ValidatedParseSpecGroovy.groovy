package io.micronaut.validation.visitor

import io.micronaut.ast.transform.test.AbstractBeanDefinitionSpec
import io.micronaut.core.beans.BeanIntrospection
import io.micronaut.inject.writer.BeanDefinitionVisitor

import java.time.LocalDate

class ValidatedParseSpecGroovy extends AbstractBeanDefinitionSpec {
    void "test constraints on beans make them @Validated"() {
        given:
        def definition = buildBeanDefinition('validateparse1.Test','''
package validateparse1
import io.micronaut.context.annotation.Executable
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank

@jakarta.inject.Singleton
class Test {
    @Executable
    void setName(@NotBlank String name) {}

    @Executable
    void setName2(@Valid String name) {}
}
''')

        expect:
        definition.findMethod("setName", String).get().hasStereotype(ValidatedParseSpec.VALIDATED_ANN)
        definition.findMethod("setName2", String).get().hasStereotype(ValidatedParseSpec.VALIDATED_ANN)
    }

    void "test annotation default values on a groovy property"() {
        given:
        BeanIntrospection beanIntrospection = buildBeanIntrospection('validateparse2.Test','''
package validateparse2;
import io.micronaut.core.annotation.Introspected
import jakarta.validation.Constraint
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Introspected
class Test {

    @ValidURLs
    List<String> webs
}

@Constraint(validatedBy = [])
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@interface ValidURLs {
    String message() default "invalid url"
}

''')

        expect:
        beanIntrospection.getProperty("webs").isPresent()
        beanIntrospection.getRequiredProperty("webs", List).annotationMetadata.getDefaultValue("validateparse2.ValidURLs", "message", String).get() == "invalid url"
    }

    void "test constraints on a declarative client makes it @Validated"() {
        given:
        def definition = buildBeanDefinition('validateparse3.ExchangeRates' + BeanDefinitionVisitor.PROXY_SUFFIX,'''
package validateparse3
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.annotation.Client
import jakarta.validation.constraints.PastOrPresent
import java.time.LocalDate

@Client("https://exchangeratesapi.io")
interface ExchangeRates {
    @Get("{date}")
    String rate(@PastOrPresent LocalDate date)
}
''')

        expect:
        definition.findMethod("rate", LocalDate).get().hasStereotype(ValidatedParseSpec.VALIDATED_ANN)
    }
}
