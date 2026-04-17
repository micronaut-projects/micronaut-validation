package io.micronaut.validation.validator.reactive

import io.micronaut.context.ApplicationContext
import io.micronaut.validation.validator.Validator
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import jakarta.validation.ConstraintViolationException
import org.reactivestreams.Publisher
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RxJava3MethodValidationSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext applicationContext = ApplicationContext.run()

    void "test reactive return type validation"() {
        given:
        BookServiceRxJava3 bookService = applicationContext.getBean(BookServiceRxJava3)

        when:
        Single<Book> single = Single.just(new Book("It"))
        Single.fromPublisher(bookService.rxReturnInvalid(single.toFlowable())).blockingGet()

        then:
        ConstraintViolationException e = thrown()
        e.message == '<return value>[].title: must not be blank'
        e.getConstraintViolations().first().propertyPath.toString() == '<return value>[].title'
    }

    void "test reactive return type no validation"() {
        given:
        BookServiceRxJava3 bookService = applicationContext.getBean(BookServiceRxJava3)

        when:
        Single<Book> single = Single.just(new Book("It"))
        bookService.rxReturnInvalidWithoutValidation(single.toFlowable()).blockingGet()

        then:
        noExceptionThrown()
    }

    void "test reactive validation with invalid simple argument"() {
        given:
        BookServiceRxJava3 bookService = applicationContext.getBean(BookServiceRxJava3)

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
        e.message == "rxSimple.title[]<publisher element>: must not be blank"
        def path = e.getConstraintViolations().first().propertyPath.iterator()
        path.next().getName() == 'rxSimple'
        path.next().getName() == 'title'
        path.next().isInIterable()
    }

    void "test reactive validation with valid argument"() {
        given:
        BookServiceRxJava3 bookService = applicationContext.getBean(BookServiceRxJava3)

        when:
        def input = Observable.just(new Book("It"))
        def book = bookService.rxValid(input).blockingFirst()

        then:
        book.title == 'It'
    }

    void "test reactive maybe validation with valid argument"() {
        given:
        BookServiceRxJava3 bookService = applicationContext.getBean(BookServiceRxJava3)

        when:
        def input = Maybe.just(new Book("It"))
        def book = bookService.rxValidMaybe(input).blockingGet()

        then:
        book.title == 'It'
    }

    void "test reactive validation with invalid argument"() {
        given:
        BookServiceRxJava3 bookService = applicationContext.getBean(BookServiceRxJava3)

        when:
        def input = Observable.just(new Book(""))
        bookService.rxValid(input).blockingFirst()

        then:
        def e = thrown(ConstraintViolationException)
        e.message == "rxValid.book[].title: must not be blank"
        e.getConstraintViolations().first().propertyPath.toString().startsWith('rxValid.book')
    }

    void "test reactive validation with invalid argument type parameter"() {
        given:
        BookServiceRxJava3 bookService = applicationContext.getBean(BookServiceRxJava3)

        when:
        def input = Single.just([new Book("It"), new Book("")])
        bookService.rxValidWithTypeParameter(input).blockingAwait()

        then:
        def e = thrown(ConstraintViolationException)
        e.message == "rxValidWithTypeParameter.books[][1].title: must not be blank"
        e.getConstraintViolations().first().propertyPath.toString().startsWith('rxValidWithTypeParameter.books')
    }

}
