package io.micronaut.validation.reflection;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.validator.DefaultValidatorFactory;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParameterNameProviderDescriptorTest {

    @Introspected
    public static class Created {
        public Created(@NotNull String value) {
        }
    }

    static final class Renaming implements ParameterNameProvider {
        @Override
        public List<String> getParameterNames(Constructor<?> constructor) {
            return List.of("renamed");
        }

        @Override
        public List<String> getParameterNames(Method method) {
            return List.of("renamed");
        }
    }

    @Test
    void aConstructorDescriptorNamesItsParametersByTheProvider() {
        try (var factory = new DefaultValidatorFactory()) {
            var validator = factory.usingContext().parameterNameProvider(new Renaming()).getValidator();
            var constructor = validator.getConstraintsForClass(Created.class).getConstraintsForConstructor(String.class);
            assertEquals("renamed", constructor.getParameterDescriptors().get(0).getName());
        }
    }
}
