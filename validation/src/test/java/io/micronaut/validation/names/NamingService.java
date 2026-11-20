package io.micronaut.validation.names;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.constraints.NotBlank;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * A bean validated with a parameter name provider of the application.
 */
@Singleton
@Requires(property = "spec.name", value = "ParameterNameProviderSpec")
public class NamingService {

    public String save(@NotBlank String name) {
        return name;
    }

    @Singleton
    @Requires(property = "spec.name", value = "ParameterNameProviderSpec")
    public static class Provider implements ParameterNameProvider {
        @Override
        public List<String> getParameterNames(Constructor<?> constructor) {
            return Collections.nCopies(constructor.getParameterCount(), "named");
        }

        @Override
        public List<String> getParameterNames(Method method) {
            return Collections.nCopies(method.getParameterCount(), "named");
        }
    }
}
