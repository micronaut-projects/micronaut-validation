package io.micronaut.docs.validation.path.model;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Introspected
public final class Listener {
    private final String description;

    @Valid
    private final
    List<Condition> conditions;

    @Valid
    @NotEmpty
    private final
    List<Task> tasks;

    Listener(String description, @Valid List<Condition> conditions, @Valid @NotEmpty List<Task> tasks) {
        this.description = description;
        this.conditions = conditions;
        this.tasks = tasks;
    }

    public static ListenerBuilder builder() {
        return new ListenerBuilder();
    }

    public String getDescription() {
        return this.description;
    }

    public @Valid List<Condition> getConditions() {
        return this.conditions;
    }

    public @Valid @NotEmpty List<Task> getTasks() {
        return this.tasks;
    }

    public String toString() {
        return "Listener(description=" + this.getDescription() + ", conditions=" + this.getConditions() + ", tasks=" + this.getTasks() + ")";
    }

    public static class ListenerBuilder {
        private String description;
        private @Valid List<Condition> conditions;
        private @Valid
        @NotEmpty List<Task> tasks;

        ListenerBuilder() {
        }

        public ListenerBuilder description(String description) {
            this.description = description;
            return this;
        }

        public ListenerBuilder conditions(@Valid List<Condition> conditions) {
            this.conditions = conditions;
            return this;
        }

        public ListenerBuilder tasks(@Valid @NotEmpty List<Task> tasks) {
            this.tasks = tasks;
            return this;
        }

        public Listener build() {
            return new Listener(this.description, this.conditions, this.tasks);
        }

        public String toString() {
            return "Listener.ListenerBuilder(description=" + this.description + ", conditions=" + this.conditions + ", tasks=" + this.tasks + ")";
        }
    }
}
