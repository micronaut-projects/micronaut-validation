package io.micronaut.validation.composition;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * A composed constraint whose member overrides a member of {@link Size} that does not have its type: {@code min}
 * is an {@code int}, and this offers a {@code String} for it. The specification requires a
 * {@code ConstraintDefinitionException}.
 */
@Constraint(validatedBy = {})
@Size(min = 5)
@Retention(RUNTIME)
@Target({FIELD, METHOD, ANNOTATION_TYPE})
public @interface Abc {
    String message() default "abc";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    @jakarta.validation.OverridesAttribute(constraint = Size.class, name = "min")
    String min() default "not-an-int";
}
