package io.micronaut.validation.validator.introspection;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * A cross-parameter constraint with a Bean Validation style container, not a {@code @Repeatable} one.
 */
@Constraint(validatedBy = FlagValidator.class)
@Target({METHOD, CONSTRUCTOR, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface Flag {

    String message() default "flagged";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    @Target({METHOD, CONSTRUCTOR, ANNOTATION_TYPE})
    @Retention(RUNTIME)
    @interface List {
        Flag[] value();
    }
}
