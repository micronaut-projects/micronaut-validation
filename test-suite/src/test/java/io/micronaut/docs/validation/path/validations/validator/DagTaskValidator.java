package io.micronaut.docs.validation.path.validations.validator;

import io.micronaut.core.annotation.AnnotationValue;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.docs.validation.path.model.Dag;
import io.micronaut.docs.validation.path.validations.DagTaskValidation;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;

@Singleton
public class DagTaskValidator implements ConstraintValidator<DagTaskValidation, Dag> {
    @Override
    public boolean isValid(
        @Nullable Dag value,
        @NonNull AnnotationValue<DagTaskValidation> annotationMetadata,
        @NonNull ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        if (value.getDagTasks() == null || value.getDagTasks().isEmpty()) {
            context.messageTemplate("No task defined");

            return false;
        }

        List<Dag.DagTask> taskDepends = value.getDagTasks();

        // Check for not existing taskId
        List<String> invalidDependencyIds = value.dagCheckNotExistTask(taskDepends);
        if (!invalidDependencyIds.isEmpty()) {
            String errorMessage = "Not existing task id in dependency: " + String.join(", ", invalidDependencyIds);
            context.messageTemplate(errorMessage);

            return false;
        }

        // Check for cyclic dependencies
        ArrayList<String> cyclicDependency = value.dagCheckCyclicDependencies(taskDepends);
        if (!cyclicDependency.isEmpty()) {
            context.buildConstraintViolationWithTemplate("Cyclic dependency detected: " + String.join(", ", cyclicDependency))
                .addPropertyNode("dagTasks")
                .addConstraintViolation();
            return false;
        }

        return true;
    }
}
