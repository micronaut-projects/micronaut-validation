package io.micronaut.docs.validation.retry;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@MicronautTest(rebuildContext = true, startApplication = false)
public class ValidatorTest {

    @Inject
    Validator validator;

    @Test
    void BUG_REPRODUCTION_CASE_validateValidBean_shouldNotFail() {
        var bean = new ValidatedBean("foobar", 42);

        assertDoesNotThrow(() -> validator.validate(bean));
        assertDoesNotThrow(() -> validator.validate(bean));
    }
}
