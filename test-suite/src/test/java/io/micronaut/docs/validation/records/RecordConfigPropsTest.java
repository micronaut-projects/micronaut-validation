package io.micronaut.docs.validation.records;

import java.util.List;
import java.util.Map;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.BeanInstantiationException;
import io.micronaut.context.exceptions.DependencyInjectionException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordConfigPropsTest {

    @Test
    void testInvalidConfig() {
        DependencyInjectionException exception = Assertions.assertThrows(DependencyInjectionException.class, () -> {
            try (ApplicationContext context = ApplicationContext.run(
                Map.of(
                    "test.url", "",
                    "urls", List.of(""),
                    "nested.port", 10
                )
            )) {
                context.getBean(MyRecordConfig.class);
            }
        });

        assertTrue(exception.getCause() instanceof BeanInstantiationException);
        assertTrue(exception.getCause().getMessage().contains("url - must not be blank"));

    }
}
