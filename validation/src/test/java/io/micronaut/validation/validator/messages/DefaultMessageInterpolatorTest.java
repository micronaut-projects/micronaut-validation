package io.micronaut.validation.validator.messages;

import io.micronaut.context.AbstractMessageSource;
import io.micronaut.context.MessageSource;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.i18n.ResourceBundleMessageSource;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.OrderUtil;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Status;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.context.CompositeMessageSource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

import static io.micronaut.core.order.Ordered.HIGHEST_PRECEDENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Property(name = "spec.name", value = "DefaultMessageInterpolatorTest")
@MicronautTest
class DefaultMessageInterpolatorTest {

    @Test
    void validationMessageCanBeLocalized(@Client("/") HttpClient httpClient,
                                         MessageSource messageSource) {
        BlockingHttpClient client = httpClient.toBlocking();
        String code = "jakarta.validation.constraints.Positive.message";
        assertEquals("Debe ser positivo", messageSource.getMessage(code, new Locale("es", "ES")).get());
        assertEquals("Must be positive", messageSource.getMessage(code, Locale.ENGLISH).get());
        Book invalid = new Book("Netty in Action", "9781617291470", 0);

        assertValidationJsonError(client,
            HttpRequest.POST("/books", invalid),
            json -> json.contains("Must be positive") && !json.contains("Debe ser positivo")
        );

        assertValidationJsonError(client,
            HttpRequest.POST("/books", invalid).header(HttpHeaders.ACCEPT_LANGUAGE, "es-ES"),
            json -> json.contains("Debe ser positivo") && !json.contains("Must be positive")
            );
    }

    void assertValidationJsonError(BlockingHttpClient client, HttpRequest<?> request, Predicate<String> predicate) {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () -> client.exchange(request));
        Optional<String> jsonOptional = ex.getResponse().getBody(String.class);
        assertTrue(jsonOptional.isPresent());
        String json = jsonOptional.get();
        assertNotNull(json);
        assertTrue(predicate.test(json));
    }

    @Requires(property = "spec.name", value = "DefaultMessageInterpolatorTest")
    @Factory
    static class MessageSourceFactory {

        @Singleton
        MessageSource createMessageSource() {
            return new ResourceBundleMessageSource("i18n.messages", HIGHEST_PRECEDENCE);
        }
    }

    @Requires(property = "spec.name", value = "DefaultMessageInterpolatorTest")
    @Controller("/books")
    static class BookController {
        @Status(HttpStatus.CREATED)
        @Post
        public void save(@Body @Valid Book book) {
        }
    }

    @Introspected
    public record Book(@NotBlank String name,
                       @Nullable String isbn,
                       @Nullable @Positive Integer pages) {
    }
}
