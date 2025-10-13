package io.micronaut.docs.validation.path.validations.validator;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.docs.validation.path.model.Flow;
import io.micronaut.docs.validation.path.model.Task;
import io.micronaut.docs.validation.path.validations.FlowValidation;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Singleton
public class FlowValidator  implements ConstraintValidator<FlowValidation, Flow> {
    @Override
    public boolean isValid(
        @Nullable Flow value,
        @NonNull AnnotationValue<FlowValidation> annotationMetadata,
        @NonNull ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        List<String> violations = new ArrayList<>();

        // task unique id
        List<String> taskIds = value.allTasksWithChilds()
            .stream()
            .map(Task::getId)
            .toList();
        List<String> taskDuplicates = taskIds
            .stream()
            .distinct()
            .filter(entry -> Collections.frequency(taskIds, entry) > 1)
            .toList();
        if (!taskDuplicates.isEmpty()) {
            violations.add("Duplicate task id with name [" + String.join(", ", taskDuplicates) + "]");
        }

        if (!violations.isEmpty()) {
            context.messageTemplate("Invalid Flow: " + String.join(", ", violations));
            return false;
        } else {
            return true;
        }
    }
}
