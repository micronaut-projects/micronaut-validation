package io.micronaut.validation.reflection;

import io.micronaut.validation.validator.DefaultValidatorFactory;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverloadReflectionTest {

    public interface Base {
        void save(@NotNull Object item);
    }

    public static class Impl implements Base {
        @Override
        public void save(Object item) {
        }

        public void save(String item) {
        }
    }

    @Test
    void anOverloadReadReflectivelyInheritsNothing() throws Exception {
        try (var factory = new DefaultValidatorFactory()) {
            var executables = factory.getValidator().forExecutables();
            Impl bean = new Impl();
            assertEquals(0, executables.validateParameters(bean, Impl.class.getMethod("save", String.class), new Object[]{null}).size());
            assertEquals(1, executables.validateParameters(bean, Impl.class.getMethod("save", Object.class), new Object[]{null}).size());
        }
    }
}
