package io.micronaut.validation.composition;

import jakarta.validation.Constraint;
import jakarta.validation.OverridesAttribute;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * A composed constraint overriding a member {@link Size} does not declare at all.
 */
@Constraint(validatedBy = {})
@Size(min = 5)
@Retention(RUNTIME)
@Target({FIELD, METHOD, ANNOTATION_TYPE})
public @interface MissingMember {
    String message() default "missing";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    @OverridesAttribute(constraint = Size.class, name = "thereIsNoSuchMember")
    int bound() default 3;
}
