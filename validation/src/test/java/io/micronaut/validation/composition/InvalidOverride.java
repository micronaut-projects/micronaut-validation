package io.micronaut.validation.composition;

import jakarta.validation.Constraint;
import jakarta.validation.OverridesAttribute;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * An override whose member has another type than the member it overrides: {@code size()} is a String, {@code Size.min} an int.
 */
@Size
@Constraint(validatedBy = {})
@Documented
@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface InvalidOverride {
    String message() default "Wrong zipcode";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    @OverridesAttribute(constraint = Size.class, name = "min")
    String size() default "5";
}
