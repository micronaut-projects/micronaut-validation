package io.micronaut.validation.validator.reactive;

import io.micronaut.context.annotation.Executable;
import jakarta.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Singleton
class BookService {
    @Executable
    CompletionStage<@Valid Book> futureSimple(CompletionStage<@NotBlank String> title) {
        return title.thenApply(Book::new);
    }

    @Executable
    CompletionStage<@Min(10) Long> futureLong(CompletionStage<@NotBlank String> title) {
        return title.thenApply(s -> 2L);
    }

    @Executable
    CompletableFuture<@Valid Book> futureValid(CompletableFuture<@Valid Book> book) {
        return book;
    }

    @Executable
    Publisher<@Valid Book> rxSimple(Publisher<@NotBlank String> title) {
        return Flux.from(title).map(Book::new);
    }

    @Executable
    Publisher<@Min(10) Long> rxLong(Publisher<@NotBlank String> title) {
        return Flux.from(title).map(x -> 2L);
    }

    @Executable
    Flux<@Valid Book> rxValid(Flux<@Valid Book> book) {
        return book;
    }

    @Executable
    Mono<Void> rxValidWithTypeParameter(Mono<List<@Valid Book>> books) {
        return books.then();
    }

    @Executable
    Mono<@Valid Book> rxValidMono(Mono<@Valid Book> book) { return book; }

    @Executable
    Publisher<@Valid Book> rxReturnInvalid(Publisher<@Valid Book> book) {
        return Flux.from(book).map(b -> new Book(""));
    }

    @Executable
    Mono<Book> rxReturnInvalidWithoutValidation(Flux<@Valid Book> books) {
        return books.collectList().map(v -> new Book(""));
    }

}


