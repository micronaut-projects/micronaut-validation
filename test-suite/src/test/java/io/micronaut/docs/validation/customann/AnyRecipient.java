package io.micronaut.docs.validation.customann;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Constraint(validatedBy = {})
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AnyRecipient {
    String MESSAGE = "io.micronaut.validation.docs.customann.AnyRecipient.message";

    String message() default "{" + MESSAGE + "}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}


