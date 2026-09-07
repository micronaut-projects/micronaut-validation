package io.micronaut.validation.composition;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * A cross-parameter constraint composing an element constraint: the two share no validation target.
 */
@Documented
@ElementOnly
@Constraint(validatedBy = ParametersOnly.Validator.class)
@Target({TYPE, METHOD, FIELD})
@Retention(RUNTIME)
public @interface ParametersOnly {
    String message() default "{constraint.notEmpty}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    @SupportedValidationTarget(ValidationTarget.PARAMETERS)
    class Validator implements ConstraintValidator<ParametersOnly, Object[]> {
        @Override
        public boolean isValid(Object[] parameters, AnnotationValue<ParametersOnly> annotationMetadata, ConstraintValidatorContext context) {
            return true;
        }
    }
}
