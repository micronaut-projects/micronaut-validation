package io.micronaut.validation;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import jakarta.validation.Constraint;

/**
 * Simple annotation to validate behavior when adding validation annotation at class level
 */
@Documented
@Target(TYPE)
@Retention(RUNTIME)
@Constraint(validatedBy = {PojoValidator.class})
public @interface ValidPojo {
}
