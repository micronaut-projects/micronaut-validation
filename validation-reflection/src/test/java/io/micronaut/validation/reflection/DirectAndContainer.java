package io.micronaut.validation.reflection;

import jakarta.validation.Constraint;
import jakarta.validation.OverridesAttribute;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * A constraint composed directly and inside its container at once, which the specification forbids.
 */
@Pattern(regexp = ".....")
@Pattern.List({@Pattern(regexp = "bar")})
@Constraint(validatedBy = {})
@Documented
@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface DirectAndContainer {
    String message() default "Wrong zipcode";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    @OverridesAttribute(constraint = Pattern.class, name = "regexp", constraintIndex = 1)
    String regex() default "\\d*";
}
