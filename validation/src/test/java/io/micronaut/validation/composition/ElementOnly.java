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

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * A constraint whose validator supports the annotated element only.
 */
@Documented
@Constraint(validatedBy = ElementOnly.Validator.class)
@Target({TYPE, METHOD, FIELD, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface ElementOnly {
    String message() default "{constraint.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    @SupportedValidationTarget(ValidationTarget.ANNOTATED_ELEMENT)
    class Validator implements ConstraintValidator<ElementOnly, Object> {
        @Override
        public boolean isValid(Object object, AnnotationValue<ElementOnly> annotationMetadata, ConstraintValidatorContext context) {
            return true;
        }
    }
}
