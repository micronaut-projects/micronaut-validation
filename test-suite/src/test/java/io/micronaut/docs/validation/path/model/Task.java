package io.micronaut.docs.validation.path.model;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Duration;
import java.util.Optional;

@Introspected
abstract public class Task {
    @NotNull
    @NotBlank
    @Pattern(regexp = "[a-zA-Z0-9_-]+")
    protected String id;

    @NotNull
    @NotBlank
    @Pattern(regexp = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*")
    protected String type;

    private String description;

    protected Duration timeout;

    protected Boolean disabled = false;

    @Valid
    private WorkerGroup workerGroup;

    public Task() {
    }

    protected Task(TaskBuilder<?, ?> b) {
        this.id = b.id;
        this.type = b.type;
        this.description = b.description;
        this.timeout = b.timeout;
        if (b.disabled$set) {
            this.disabled = b.disabled$value;
        } else {
            this.disabled = $default$disabled();
        }
        this.workerGroup = b.workerGroup;
    }

    private static Boolean $default$disabled() {
        return false;
    }

    public Optional<Task> findById(String id) {
        if (this.getId().equals(id)) {
            return Optional.of(this);
        }

        if (this.isFlowable()) {
            Optional<Task> childs = ((FlowableTask<?>) this).allChildTasks()
                .stream()
                .map(t -> t.findById(id))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();

            if (childs.isPresent()) {
                return childs;
            }
        }

        return Optional.empty();
    }

    public boolean isFlowable() {
        return this instanceof FlowableTask;
    }

    public @NotNull @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]+") String getId() {
        return this.id;
    }

    public @NotNull @NotBlank @Pattern(regexp = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*") String getType() {
        return this.type;
    }

    public String getDescription() {
        return this.description;
    }

    public Duration getTimeout() {
        return this.timeout;
    }

    public Boolean getDisabled() {
        return this.disabled;
    }

    public @Valid WorkerGroup getWorkerGroup() {
        return this.workerGroup;
    }

    public static abstract class TaskBuilder<C extends Task, B extends TaskBuilder<C, B>> {
        private @NotNull
        @NotBlank
        @Pattern(regexp = "[a-zA-Z0-9_-]+") String id;
        private @NotNull
        @NotBlank
        @Pattern(regexp = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*") String type;
        private String description;
        private Duration timeout;
        private Boolean disabled$value;
        private boolean disabled$set;
        private @Valid WorkerGroup workerGroup;

        private static void $fillValuesFromInstanceIntoBuilder(Task instance, TaskBuilder<?, ?> b) {
            b.id(instance.id);
            b.type(instance.type);
            b.description(instance.description);
            b.timeout(instance.timeout);
            b.disabled(instance.disabled);
            b.workerGroup(instance.workerGroup);
        }

        public B id(@NotNull @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]+") String id) {
            this.id = id;
            return self();
        }

        public B type(@NotNull @NotBlank @Pattern(regexp = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*") String type) {
            this.type = type;
            return self();
        }

        public B description(String description) {
            this.description = description;
            return self();
        }

        public B timeout(Duration timeout) {
            this.timeout = timeout;
            return self();
        }

        public B disabled(Boolean disabled) {
            this.disabled$value = disabled;
            this.disabled$set = true;
            return self();
        }

        public B workerGroup(@Valid WorkerGroup workerGroup) {
            this.workerGroup = workerGroup;
            return self();
        }

        protected B $fillValuesFrom(C instance) {
            TaskBuilder.$fillValuesFromInstanceIntoBuilder(instance, this);
            return self();
        }

        protected abstract B self();

        public abstract C build();

        public String toString() {
            return "Task.TaskBuilder(id=" + this.id + ", type=" + this.type + ", description=" + this.description + ", timeout=" + this.timeout + ", disabled$value=" + this.disabled$value + ", workerGroup=" + this.workerGroup + ")";
        }
    }
}
