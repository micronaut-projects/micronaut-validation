package io.micronaut.validation.validator.reactive

import io.micronaut.context.ApplicationContext
import io.micronaut.validation.validator.Validator
import jakarta.validation.ConstraintViolationException
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

class ReactiveMethodValidationSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext applicationContext = ApplicationContext.run()

    void "test reactive return type validation"() {
        given:
        BookService bookService = applicationContext.getBean(BookService)

        when:
        Mono<Book> mono = Mono.just(new Book("It"))
        Mono.from(bookService.rxReturnInvalid(mono)).block()

        then:
        ConstraintViolationException e = thrown()
        e.message == '<return value>[]<publisher element>.title: must not be blank'
        e.getConstraintViolations().first().propertyPath.toString() == '<return value>[]<publisher element>.title'
    }

    void "test reactive return type no validation"() {
        given:
        BookService bookService = applicationContext.getBean(BookService)

        when:
        Flux<Book> input = Flux.just(new Book("It"))
        Mono.from(bookService.rxReturnInvalidWithoutValidation(input)).block()

        then:
        noExceptionThrown()
    }

    void "test reactive validation with invalid simple argument"() {
        given:
        BookService bookService = applicationContext.getBean(BookService)

        when:
        var validator = applicationContext.getBean(Validator)
        var violations = validator.forExecutables().validateParameters(
                bookService,
                BookService.class.getDeclaredMethod("rxSimple", Publisher<String>),
                [Mono.just("")] as Object[]
        )

        then: "No errors because publisher is not executed"
        violations.size() == 0

        when:
        Mono.from(bookService.rxSimple(Mono.just(""))).block()

        then:
        def e = thrown(ConstraintViolationException)
        e.message == "rxSimple.title[]<publisher element>: must not be blank"
        def path = e.getConstraintViolations().first().propertyPath.iterator()
        path.next().getName() == 'rxSimple'
        path.next().getName() == 'title'
        path.next().isInIterable()
    }

    void "test reactive validation with valid argument"() {
        given:
        BookService bookService = applicationContext.getBean(BookService)

        when:
        def input = Flux.just(new Book("It"))
        def book = Mono.from(bookService.rxValid(input)).block()

        then:
        book.title == 'It'
    }

    void "test reactive mono validation with valid argument"() {
        given:
        BookService bookService = applicationContext.getBean(BookService)

        when:
        def input = Mono.just(new Book("It"))
        def book = Mono.from(bookService.rxValidMono(input)).block()

        then:
        book.title == 'It'
    }

    void "test reactive validation with invalid argument"() {
        given:
        BookService bookService = applicationContext.getBean(BookService)

        when:
        def input = Flux.just(new Book(""))
        Mono.from(bookService.rxValid(input)).block()

        then:
        def e = thrown(ConstraintViolationException)
        e.message == "rxValid.book[]<publisher element>.title: must not be blank"
        e.getConstraintViolations().first().propertyPath.toString().startsWith('rxValid.book')
    }

    void "test reactive validation with invalid argument type parameter"() {
        given:
        BookService bookService = applicationContext.getBean(BookService)

        when:
        def input = Mono.just([new Book("It"), new Book("")])
        Mono.from(bookService.rxValidWithTypeParameter(input)).block()

        then:
        def e = thrown(ConstraintViolationException)
        e.message == "rxValidWithTypeParameter.books[]<publisher element>[1]<list element>.title: must not be blank"
        e.getConstraintViolations().first().propertyPath.toString().startsWith('rxValidWithTypeParameter.books')
    }

    void "test future validation with invalid simple argument"() {
        given:
        BookService bookService = applicationContext.getBean(BookService)

        when:
        bookService.futureSimple(CompletableFuture.completedFuture("")).get()

        then:
        ExecutionException e = thrown()
        e.cause instanceof ConstraintViolationException
        e.cause.message == "futureSimple.title[]<completion stage element>: must not be blank"
        e.cause.getConstraintViolations().first().propertyPath.toString().startsWith('futureSimple.title')
    }

    void "test future validation of return"() {
        given:
        BookService bookService = applicationContext.getBean(BookService)

        when:
        bookService.futureLong(CompletableFuture.completedFuture("abc")).get()

        then:
        ExecutionException e = thrown()
        e.cause instanceof ConstraintViolationException
        e.cause.message == "<completion stage element>: must be greater than or equal to 10"
    }

    void "test future validation with invalid argument"() {
        given:
        BookService bookService = applicationContext.getBean(BookService)

        when:
        bookService.futureValid(CompletableFuture.completedFuture(new Book(""))).get()

        then:
        ExecutionException e = thrown()
        e.cause instanceof ConstraintViolationException
        e.cause.message == "futureValid.book[]<completion stage element>.title: must not be blank"
        e.cause.getConstraintViolations().first().propertyPath.toString().startsWith('futureValid.book')
    }
}
