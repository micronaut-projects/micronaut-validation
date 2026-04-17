package io.micronaut.validation.validator.reactive;

import io.micronaut.context.annotation.Executable;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

@Singleton
class BookServiceRxJava3 {

    @Executable
    Publisher<@Valid Book> rxSimple(Publisher<@NotBlank String> title) {
        return Single.fromPublisher(title).map(Book::new).toFlowable();
    }

    @Executable
    Observable<@Valid Book> rxValid(Observable<@Valid Book> book) {
        return book;
    }

    @Executable
    Completable rxValidWithTypeParameter(Single<List<@Valid Book>> books) {
        return books.ignoreElement();
    }

    @Executable
    Maybe<@Valid Book> rxValidMaybe(Maybe<@Valid Book> book) { return book; }

    @Executable
    Publisher<@Valid Book> rxReturnInvalid(Publisher<@Valid Book> book) {
        return Flowable.fromPublisher(book).map(b -> new Book(""));
    }

    @Executable
    Maybe<Book> rxReturnInvalidWithoutValidation(Flowable<@Valid Book> books) {
        return books.firstElement().map(v -> new Book(""));
    }

}


