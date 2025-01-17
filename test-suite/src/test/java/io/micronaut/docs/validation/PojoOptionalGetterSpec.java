package io.micronaut.docs.validation;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Singleton;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Property(name = "spec.name", value = "PojoOptionalGetterSpec")
@MicronautTest
class PojoOptionalGetterSpec {

    @Test
    void pojoCanHaveGetterWhichReturnsAnOptional(MockService service) {
        assertDoesNotThrow(() -> service.validate(new ListingArguments(0)));
    }

    @Requires(property = "spec.name", value = "PojoOptionalGetterSpec")
    @Singleton
    static class MockService {

        void validate(@Valid ListingArguments arguments) {

        }
    }

}
