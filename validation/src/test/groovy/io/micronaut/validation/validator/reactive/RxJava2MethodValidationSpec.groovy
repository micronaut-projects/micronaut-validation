package io.micronaut.validation.validator.reactive

import io.micronaut.context.ApplicationContext
import io.micronaut.validation.validator.Validator
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import javax.validation.ConstraintViolationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.regex.Pattern

class RxJava2MethodValidationSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext applicationContext = ApplicationContext.run()

    void "test reactive return type validation"() {
        given:
        BookServiceRxJava2 bookService = applicationContext.getBean(BookServiceRxJava2)

        when:
        Single<Book> single = Single.just(new Book("It"))
        Single.fromPublisher(bookService.rxReturnInvalid(single.toFlowable())).blockingGet()

        then:
        ConstraintViolationException e = thrown()
        e.message == 'publisher[]<T Book>.title: must not be blank'
        e.getConstraintViolations().first().propertyPath.toString() == 'publisher[]<T Book>.title'
    }

    void "test reactive return type no validation"() {
        given:
        BookServiceRxJava2 bookService = applicationContext.getBean(BookServiceRxJava2)

        when:
        Single<Book> single = Single.just(new Book("It"))
        bookService.rxReturnInvalidWithoutValidation(single.toFlowable()).blockingGet()

        then:
        noExceptionThrown()
    }

    void "test reactive validation with invalid simple argument"() {
        given:
        BookServiceRxJava2 bookService = applicationContext.getBean(BookServiceRxJava2)

        when:
        var validator = applicationContext.getBean(Validator)
        var violations = validator.forExecutables().validateParameters(
                bookService,
                BookService.class.getDeclaredMethod("rxSimple", Publisher<String>),
                [Flowable.just("")] as Object[]
        )

        then: "No errors because publisher is not executed"
        violations.size() == 0

        when:
        Single.fromPublisher(bookService.rxSimple(Single.just("").toFlowable())).blockingGet()

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
        BookServiceRxJava2 bookService = applicationContext.getBean(BookServiceRxJava2)

        when:
        def input = Observable.just(new Book("It"))
        def book = bookService.rxValid(input).blockingFirst()

        then:
        book.title == 'It'
    }

    void "test reactive maybe validation with valid argument"() {
        given:
        BookServiceRxJava2 bookService = applicationContext.getBean(BookServiceRxJava2)

        when:
        def input = Maybe.just(new Book("It"))
        def book = bookService.rxValidMaybe(input).blockingGet()

        then:
        book.title == 'It'
    }

    void "test reactive validation with invalid argument"() {
        given:
        BookServiceRxJava2 bookService = applicationContext.getBean(BookServiceRxJava2)

        when:
        def input = Observable.just(new Book(""))
        bookService.rxValid(input).blockingFirst()

        then:
        def e = thrown(ConstraintViolationException)
        Pattern.matches('rxValid.book\\[]<T .*Book>.title: must not be blank', e.message)
        e.getConstraintViolations().first().propertyPath.toString().startsWith('rxValid.book')
    }

    void "test reactive validation with invalid argument type parameter"() {
        given:
        BookServiceRxJava2 bookService = applicationContext.getBean(BookServiceRxJava2)

        when:
        def input = Single.just([new Book("It"), new Book("")])
        bookService.rxValidWithTypeParameter(input).blockingAwait()

        then:
        def e = thrown(ConstraintViolationException)
        Pattern.matches('rxValidWithTypeParameter.books\\[]<T List>\\[1]<E Book>.title: must not be blank', e.message)
        e.getConstraintViolations().first().propertyPath.toString().startsWith('rxValidWithTypeParameter.books')
    }

}
