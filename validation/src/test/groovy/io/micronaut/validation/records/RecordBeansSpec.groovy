package io.micronaut.validation.records


import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.PropertySource
import io.micronaut.context.exceptions.DependencyInjectionException
import io.micronaut.core.reflect.ClassUtils
import io.micronaut.inject.BeanDefinition
import io.micronaut.inject.ValidatedBeanDefinition
import io.micronaut.inject.validation.BeanDefinitionValidator
import io.micronaut.validation.validator.Validator
import spock.lang.Specification

class RecordBeansSpec extends Specification {

    void 'test configuration properties as record'() {
        given:
        ApplicationContext context = ApplicationContext.run(['spec.name': getClass().getSimpleName()])
        BeanDefinition<?> definition = context.getBeanDefinition(Test)
        when:
        context.registerSingleton(BeanDefinitionValidator, Validator.getInstance())
        context.environment.addPropertySource(PropertySource.of("test",  ['foo.num': 10, 'foo.name':'test']))
        context.getBean(Test)

        then:
        definition instanceof ValidatedBeanDefinition
        ClassUtils.isPresent('io.micronaut.validation.records.$Test$Introspection', context.getClassLoader())
        ClassUtils.isPresent('io.micronaut.validation.records.$Test$Definition', context.getClassLoader())
        !ClassUtils.isPresent('io.micronaut.validation.records.$Test$Definition$Intercepted', context.getClassLoader())

        and:
        def e = thrown(DependencyInjectionException)
        e.cause.message.contains('must be greater than or equal to 20')

        when:
        context.environment.addPropertySource(PropertySource.of("test",  ['foo.num': 25, 'foo.name':'test']))
        def bean = context.getBean(Test)

        then:
        definition.constructor.arguments.length == 4
        bean.num() == 25
        bean.conversionService() != null
        bean.beanContext().is(context)

        cleanup:
        context.close()
    }

    void 'test record bean with nullable annotations'() {
        given:
        ApplicationContext context = ApplicationContext.run(['spec.name': getClass().getSimpleName()])

        when:
        BeanDefinition<?> definition = context.getBeanDefinition(Test2)

        then:
        !(definition instanceof ValidatedBeanDefinition)
        !ClassUtils.isPresent('io.micronaut.validation.records.$Test2$Introspection', context.getClassLoader())
        ClassUtils.isPresent('io.micronaut.validation.records.$Test2$Definition', context.getClassLoader())
        !ClassUtils.isPresent('io.micronaut.validation.records.$Test2$Definition$Intercepted', context.getClassLoader())

        cleanup:
        context.close()
    }

}
