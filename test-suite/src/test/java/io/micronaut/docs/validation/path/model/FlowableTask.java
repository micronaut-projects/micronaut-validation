package io.micronaut.docs.validation.path.model;

import java.util.List;

public interface FlowableTask<T extends Output> {
    List<Task> getErrors();

    List<Task> allChildTasks();
}
