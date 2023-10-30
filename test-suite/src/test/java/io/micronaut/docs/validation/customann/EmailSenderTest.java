package io.micronaut.docs.validation.customann;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@MicronautTest(startApplication = false)
class EmailSenderTest {

    @Test
    void defaultMessageIsUsed(EmailSender emailSender) {
        Executable e = () -> emailSender.send(new Email("", ""));
        ConstraintViolationException thrown = assertThrows(ConstraintViolationException.class, e);
        assertEquals(Collections.singletonList(EmailMessages.ANY_RECIPIENT_MESSAGE), thrown.getConstraintViolations().stream().map(ConstraintViolation::getMessage).toList());
    }
}
