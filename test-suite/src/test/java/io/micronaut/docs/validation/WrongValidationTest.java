package io.micronaut.docs.validation;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Property(name = "spec.name", value = "WrongValidationTest")
@MicronautTest
class WrongValidationTest {

    @Inject
    ValidatedService validatedService;

    @Test
    void validateEvent() {
        assertDoesNotThrow(() -> validatedService.send(new IntrospectedEvent()));

        var ex = assertThrows(ConstraintViolationException.class, () -> validatedService.send(new NonIntrospectedEvent()));

        assertTrue(ex.getMessage().contains("NonIntrospectedEvent"));
    }

    @Requires(property = "spec.name", value = "WrongValidationTest")
    @Singleton
    @Validated
    static class ValidatedService {

        <E> void send(@Valid E event) {

        }
    }

    @Introspected
    static class IntrospectedEvent {}

    static class NonIntrospectedEvent {}
}