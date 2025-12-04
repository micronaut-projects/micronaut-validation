package io.micronaut.docs.validation.path.validations.validator;

import io.micronaut.core.annotation.AnnotationValue;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.docs.validation.path.model.WorkerGroup;
import io.micronaut.docs.validation.path.validations.WorkerGroupValidation;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.inject.Singleton;

@Singleton
public class WorkerGroupValidator implements ConstraintValidator<WorkerGroupValidation, WorkerGroup> {
    @Override
    public boolean isValid(
        @Nullable WorkerGroup value,
        @NonNull AnnotationValue<WorkerGroupValidation> annotationMetadata,
        @NonNull ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        context.messageTemplate("Worker Group is an Enterprise Edition functionality");
        return false;
    }
}
