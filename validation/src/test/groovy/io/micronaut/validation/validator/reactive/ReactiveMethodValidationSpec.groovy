package io.micronaut.validation.validator.reactive

import io.micronaut.context.ApplicationContext
import io.micronaut.validation.validator.Validator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import javax.validation.ConstraintViolationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.regex.Pattern
import org.reactivestreams.Publisher

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
        e.message == 'publisher[]<T Book>.title: must not be blank'
        e.getConstraintViolations().first().propertyPath.toString() == 'publisher[]<T Book>.title'
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

        Mono.from(bookService.rxSimple(Mono.just(""))).block()

        then:
        def e = thrown(ConstraintViolationException)
        Pattern.matches('rxSimple.title\\[]<T [^>]*String>: must not be blank', e.message)
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
        Pattern.matches('rxValid.book\\[]<T .*Book>.title: must not be blank', e.message)
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
        Pattern.matches('rxValidWithTypeParameter.books\\[]<T List>\\[1]<E Book>.title: must not be blank', e.message)
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

        Pattern.matches('futureSimple.title\\[]<T .*String>: must not be blank', e.cause.message)
        e.cause.getConstraintViolations().first().propertyPath.toString().startsWith('futureSimple.title')
    }

    void "test future validation with invalid argument"() {
        given:
        BookService bookService = applicationContext.getBean(BookService)

        when:
        bookService.futureValid(CompletableFuture.completedFuture(new Book(""))).get()

        then:
        ExecutionException e = thrown()
        e.cause instanceof ConstraintViolationException

        Pattern.matches('futureValid.book\\[]<T .*Book>.title: must not be blank', e.cause.message);
        e.cause.getConstraintViolations().first().propertyPath.toString().startsWith('futureValid.book')
    }
}
