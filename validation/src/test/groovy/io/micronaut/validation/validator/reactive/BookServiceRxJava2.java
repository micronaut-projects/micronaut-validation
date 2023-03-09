package io.micronaut.validation.validator.reactive;

import io.micronaut.context.annotation.Executable;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

@Singleton
class BookServiceRxJava2 {

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


