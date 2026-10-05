package io.micronaut.validation.validator.reload

import io.micronaut.context.ApplicationContext
import io.micronaut.context.RuntimeBeanDefinition
import io.micronaut.context.reload.ClassChange
import io.micronaut.context.reload.ClassChangeEvent
import io.micronaut.context.reload.ReloadStrategy
import io.micronaut.core.type.Argument
import io.micronaut.validation.ValidatingInterceptor
import io.micronaut.validation.validator.Validator
import io.micronaut.validation.validator.constraints.ConstraintValidator
import io.micronaut.validation.validator.constraints.ConstraintValidatorRegistry
import io.micronaut.validation.validator.constraints.InternalConstraintValidatorFactory
import io.micronaut.validation.validator.extractors.ValueExtractorRegistry
import jakarta.validation.UnexpectedTypeException
import jakarta.validation.ValidatorFactory
import jakarta.validation.valueextraction.ValueExtractor
import spock.lang.Specification

import java.util.function.Supplier

class ValidationReloadSpec extends Specification {

    private static final String RELOADER = 'io.micronaut.validation.validator.DevelopmentValidationReloader'

    void "in development mode a validator and an extractor registered at runtime are used by the next validation"() {
        given:
        ApplicationContext context = ApplicationContext.builder()
            .properties('micronaut.dev.enabled': true)
            .trackBeanDependencies(true)
            .start()
        Validator validator = context.getBean(Validator)
        ValueExtractorRegistry extractors = context.getBean(ValueExtractorRegistry)
        ValidatorFactory validatorFactory = context.getBean(ValidatorFactory)
        ValidatingInterceptor interceptor = context.getBean(ValidatingInterceptor)

        expect: 'the reloader exists only in development mode'
        context.containsBean(reloader())
        extractors.findValueExtractors(ReloadBox).isEmpty()

        when: 'there is no validator for the constraint yet, and the registry caches that answer'
        validator.validate(new ReloadCounter(2))

        then:
        thrown(UnexpectedTypeException)

        when:
        def registered = register(context)
        Validator fresh = context.getBean(Validator)

        then: 'the validator and what it is built from were recreated, and read the new definitions'
        !fresh.is(validator)
        !context.getBean(ValueExtractorRegistry).is(extractors)
        !context.getBean(ValidatorFactory).is(validatorFactory)
        !context.getBean(ValidatingInterceptor).is(interceptor)
        context.getBean(ValidatorFactory).validator.is(fresh)
        fresh.validate(new ReloadCounter(2))*.propertyPath*.toString() == ['count']
        fresh.validate(new ReloadCounter(3)).isEmpty()
        context.getBean(ConstraintValidatorRegistry).findConstraintValidator(ReloadOdd, Integer).get().is(registered.validator)
        context.getBean(ValueExtractorRegistry).findValueExtractors(ReloadBox)*.valueExtractor() == [registered.extractor]

        and: 'the group sequences and the validators of the constraints still resolve'
        fresh.validate(new ReloadBook("Dune", 41), ReloadOrdered)*.propertyPath*.toString() == ['title']
        fresh.validate(new ReloadBook("Dun", 41), ReloadOrdered)*.propertyPath*.toString() == ['pages']

        and: 'the retired ones are left as they were: nothing on their path changed'
        extractors.findValueExtractors(ReloadBox).isEmpty()

        cleanup:
        context.close()
    }

    void "in development mode a context that does not track bean dependencies keeps the validation beans, rather than replace them under the beans that received them"() {
        given:
        ApplicationContext context = ApplicationContext.builder()
            .properties('micronaut.dev.enabled': true)
            .trackBeanDependencies(false)
            .start()
        Validator validator = context.getBean(Validator)
        ValueExtractorRegistry extractors = context.getBean(ValueExtractorRegistry)
        ValidatorFactory validatorFactory = context.getBean(ValidatorFactory)
        ValidatingInterceptor interceptor = context.getBean(ValidatingInterceptor)

        expect:
        context.containsBean(reloader())

        when:
        register(context)

        then: 'nothing is recreated: the change is read after a restart'
        context.getBean(Validator).is(validator)
        context.getBean(ValueExtractorRegistry).is(extractors)
        context.getBean(ValidatorFactory).is(validatorFactory)
        context.getBean(ValidatingInterceptor).is(interceptor)
        extractors.findValueExtractors(ReloadBox).isEmpty()

        cleanup:
        context.close()
    }

    void "a bean that received the validator is recreated on top of the new one through the dependency graph"() {
        given:
        ApplicationContext context = ApplicationContext.builder()
            .properties('micronaut.dev.enabled': true, 'validation.reload.holder': true)
            .trackBeanDependencies(true)
            .start()
        ReloadValidatorHolder holder = context.getBean(ReloadValidatorHolder)

        when:
        holder.validator.validate(new ReloadCounter(2))

        then:
        thrown(UnexpectedTypeException)

        when:
        register(context)
        ReloadValidatorHolder recreated = context.getBean(ReloadValidatorHolder)

        then: 'the holder was destroyed with the validator it received, and the next one gets the new validator'
        !recreated.is(holder)
        recreated.validator.is(context.getBean(Validator))
        recreated.validator.validate(new ReloadCounter(2))*.propertyPath*.toString() == ['count']

        cleanup:
        context.close()
    }

    void "a class change applied in place that retires a loader or redefines a validated type recreates the validator, and a restart or an unrelated redefinition does not"() {
        given:
        ApplicationContext context = ApplicationContext.builder()
            .properties('micronaut.dev.enabled': true)
            .trackBeanDependencies(true)
            .start()
        Validator validator = context.getBean(Validator)
        def factory = context.getBean(InternalConstraintValidatorFactory)
        ValidatingInterceptor interceptor = context.getBean(ValidatingInterceptor)
        validator.validate(new ReloadBook("Dune", 41), ReloadOrdered)

        when: 'the application restarts: the new context has a new validator'
        context.publishEvent(classChange([ReloadBook.classLoader] as Set, [], ReloadStrategy.RESTART))

        then:
        context.getBean(Validator).is(validator)
        context.getBean(InternalConstraintValidatorFactory).is(factory)
        context.getBean(ValidatingInterceptor).is(interceptor)

        when: 'a class that is not validated is redefined in place, which retires no loader'
        context.publishEvent(classChange([] as Set, [new ClassChange(ValidationReloadSpec.name, ClassChange.Kind.MODIFIED)], ReloadStrategy.RELOAD))

        then:
        context.getBean(Validator).is(validator)

        when: 'a validated type is redefined in place'
        context.publishEvent(classChange([] as Set, [new ClassChange(ReloadBook.name, ClassChange.Kind.MODIFIED)], ReloadStrategy.RELOAD))
        Validator afterRedefinition = context.getBean(Validator)

        then:
        !afterRedefinition.is(validator)

        when: 'a bean that validates its methods is redefined in place'
        ValidatingInterceptor beforeService = context.getBean(ValidatingInterceptor)
        context.getBean(ReloadService).name()
        context.publishEvent(classChange([] as Set, [new ClassChange(ReloadService.name, ClassChange.Kind.MODIFIED)], ReloadStrategy.RELOAD))

        then: 'the interceptor, which caches what it decided for each method, is replaced'
        !context.getBean(ValidatingInterceptor).is(beforeService)
        context.getBean(ReloadService).name() == 'reload'

        when: 'a reload retires the loader of the test classes'
        context.publishEvent(classChange([ReloadBook.classLoader] as Set, [], ReloadStrategy.RELOAD))
        Validator afterRetirement = context.getBean(Validator)

        then: 'the validator, the constraint validator factory and the interceptor, which cache by class, method and metadata, are replaced'
        !afterRetirement.is(afterRedefinition)
        !context.getBean(InternalConstraintValidatorFactory).is(factory)
        !context.getBean(ValidatingInterceptor).is(interceptor)

        and: 'validation resolves everything again'
        afterRetirement.validate(new ReloadBook("Dune", 41), ReloadOrdered)*.propertyPath*.toString() == ['title']
        afterRetirement.validate(new ReloadBook("Dun", 41), ReloadOrdered)*.propertyPath*.toString() == ['pages']

        cleanup:
        context.close()
    }

    void "outside development mode there is no reloader and the validation beans read the definitions once, as they always have"() {
        given:
        ApplicationContext context = ApplicationContext.run()
        Validator validator = context.getBean(Validator)
        ConstraintValidatorRegistry validators = context.getBean(ConstraintValidatorRegistry)
        ValueExtractorRegistry extractors = context.getBean(ValueExtractorRegistry)
        ValidatingInterceptor interceptor = context.getBean(ValidatingInterceptor)

        expect:
        !context.containsBean(reloader())
        validators.findConstraintValidator(ReloadOdd, Integer).filter { it != ConstraintValidator.VALID }.isEmpty()
        extractors.findValueExtractors(ReloadBox).isEmpty()

        when:
        register(context)
        context.publishEvent(classChange([ReloadBook.classLoader] as Set, [], ReloadStrategy.RELOAD))

        then:
        context.getBean(Validator).is(validator)
        context.getBean(ConstraintValidatorRegistry).is(validators)
        context.getBean(ValueExtractorRegistry).is(extractors)
        context.getBean(ValidatingInterceptor).is(interceptor)
        validators.findConstraintValidator(ReloadOdd, Integer).filter { it != ConstraintValidator.VALID }.isEmpty()
        extractors.findValueExtractors(ReloadBox).isEmpty()

        cleanup:
        context.close()
    }

    private static Class<?> reloader() {
        return Class.forName(RELOADER)
    }

    private static Map<String, Object> register(ApplicationContext context) {
        ConstraintValidator<ReloadOdd, Integer> validator = { value, annotation, ctx -> value == null || value % 2 == 1 } as ConstraintValidator<ReloadOdd, Integer>
        ValueExtractor<ReloadBox> extractor = { box, receiver -> receiver.value(null, box.value) } as ValueExtractor<ReloadBox>
        context.registerBeanDefinition(RuntimeBeanDefinition.builder(ConstraintValidator, (Supplier<ConstraintValidator>) { validator })
            .typeArguments(Argument.of(ReloadOdd), Argument.of(Integer))
            .build())
        context.registerBeanDefinition(RuntimeBeanDefinition.builder(ValueExtractor, (Supplier<ValueExtractor>) { extractor })
            .typeArguments(Argument.of(ReloadBox, Argument.OBJECT_ARGUMENT))
            .build())
        return [validator: validator, extractor: extractor]
    }

    private static ClassChangeEvent classChange(Set<ClassLoader> retired, List<ClassChange> changes, ReloadStrategy strategy) {
        return new ClassChangeEvent(ValidationReloadSpec, 1, retired, ReloadBook.classLoader, changes, strategy)
    }
}
