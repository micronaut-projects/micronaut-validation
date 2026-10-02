package io.micronaut.docs.validation.path.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.docs.validation.path.validations.FlowValidation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Introspected
@FlowValidation
public class Flow implements DeletedInterface {

    @NotNull
    @NotBlank
    @Pattern(regexp = "[a-zA-Z0-9._-]+")
    String id;

    @NotNull
    @Pattern(regexp = "[a-z0-9._-]+")
    String namespace;

    @Min(value = 1)
    Integer revision;

    String description;

    List<Label> labels;

    Map<String, Object> variables;

    @Valid
    @NotEmpty
    List<Task> tasks;

    @Valid
    List<Task> errors;

    @Valid
    List<Listener> listeners;

    @Valid
    List<AbstractTrigger> triggers;

    List<TaskDefault> taskDefaults;

    @NotNull
    boolean disabled = false;

    @NotNull
    boolean deleted = false;

    public Flow(@NotNull @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]+") String id, @NotNull @Pattern(regexp = "[a-z0-9._-]+") String namespace, @Min(value = 1) Integer revision, String description, List<Label> labels, Map<String, Object> variables, @Valid @NotEmpty List<Task> tasks, @Valid List<Task> errors, @Valid List<Listener> listeners, @Valid List<AbstractTrigger> triggers, List<TaskDefault> taskDefaults, @NotNull boolean disabled, @NotNull boolean deleted) {
        this.id = id;
        this.namespace = namespace;
        this.revision = revision;
        this.description = description;
        this.labels = labels;
        this.variables = variables;
        this.tasks = tasks;
        this.errors = errors;
        this.listeners = listeners;
        this.triggers = triggers;
        this.taskDefaults = taskDefaults;
        this.disabled = disabled;
        this.deleted = deleted;
    }

    public Flow() {
    }

    protected Flow(FlowBuilder<?, ?> b) {
        this.id = b.id;
        this.namespace = b.namespace;
        this.revision = b.revision;
        this.description = b.description;
        this.labels = b.labels;
        this.variables = b.variables;
        this.tasks = b.tasks;
        this.errors = b.errors;
        this.listeners = b.listeners;
        this.triggers = b.triggers;
        this.taskDefaults = b.taskDefaults;
        if (b.disabled$set) {
            this.disabled = b.disabled$value;
        } else {
            this.disabled = $default$disabled();
        }
        if (b.deleted$set) {
            this.deleted = b.deleted$value;
        } else {
            this.deleted = $default$deleted();
        }
    }

    private static boolean $default$disabled() {
        return false;
    }

    private static boolean $default$deleted() {
        return false;
    }

    public static FlowBuilder<?, ?> builder() {
        return new FlowBuilderImpl();
    }

    public Logger logger() {
        return LoggerFactory.getLogger("flow." + this.id);
    }

    public static String uid(String namespace, String id, Optional<Integer> revision) {
        return String.join("_", Arrays.asList(
            namespace,
            id,
            String.valueOf(revision.orElse(-1))
        ));
    }

    public static String uidWithoutRevision(String namespace, String id) {
        return String.join("_", Arrays.asList(
            namespace,
            id
        ));
    }

    public Stream<String> allTypes() {
        return Stream.of(
                Optional.ofNullable(triggers).orElse(Collections.emptyList()).stream().map(AbstractTrigger::getType),
                allTasks().map(Task::getType),
                Optional.ofNullable(taskDefaults).orElse(Collections.emptyList()).stream().map(TaskDefault::getType)
            ).reduce(Stream::concat).orElse(Stream.empty())
            .distinct();
    }

    public Stream<Task> allTasks() {
        return Stream.of(
                this.tasks != null ? this.tasks : new ArrayList<Task>(),
                this.errors != null ? this.errors : new ArrayList<Task>(),
                this.listenersTasks()
            )
            .flatMap(Collection::stream);
    }

    public List<Task> allTasksWithChilds() {
        return allTasks()
            .flatMap(this::allTasksWithChilds)
            .collect(Collectors.toList());
    }

    private Stream<Task> allTasksWithChilds(Task task) {
        if (task == null) {
            return Stream.empty();
        } else if (task.isFlowable()) {
            Stream<Task> taskStream = ((FlowableTask<?>) task).allChildTasks()
                .stream()
                .flatMap(this::allTasksWithChilds);

            return Stream.concat(
                Stream.of(task),
                taskStream
            );
        } else {
            return Stream.of(task);
        }
    }

    public List<String> allTriggerIds() {
        return this.triggers != null ? this.triggers.stream()
            .map(AbstractTrigger::getId)
            .collect(Collectors.toList()) : new ArrayList<>();
    }

    public List<String> allTasksWithChildsAndTriggerIds() {
        return Stream.concat(
                this.allTasksWithChilds().stream()
                    .map(Task::getId),
                this.allTriggerIds().stream()
            )
            .collect(Collectors.toList());
    }

    public List<Task> allErrorsWithChilds() {
        var allErrors = allTasksWithChilds().stream()
            .filter(task -> task.isFlowable() && ((FlowableTask<?>) task).getErrors() != null)
            .flatMap(task -> ((FlowableTask<?>) task).getErrors().stream())
            .collect(Collectors.toCollection(ArrayList::new));

        if (this.getErrors() != null && !this.getErrors().isEmpty()) {
            allErrors.addAll(this.getErrors());
        }

        return allErrors;
    }


    private List<Task> listenersTasks() {
        if (this.getListeners() == null) {
            return new ArrayList<>();
        }

        return this.getListeners()
            .stream()
            .flatMap(listener -> listener.getTasks().stream())
            .collect(Collectors.toList());
    }

    public Flow toDeleted() {
        return new Flow(
            this.id,
            this.namespace,
            this.revision + 1,
            this.description,
            this.labels,
            this.variables,
            this.tasks,
            this.errors,
            this.listeners,
            this.triggers,
            this.taskDefaults,
            this.disabled,
            true
        );
    }

    public @NotNull @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]+") String getId() {
        return this.id;
    }

    public @NotNull @Pattern(regexp = "[a-z0-9._-]+") String getNamespace() {
        return this.namespace;
    }

    public @Min(value = 1) Integer getRevision() {
        return this.revision;
    }

    public String getDescription() {
        return this.description;
    }

    public List<Label> getLabels() {
        return this.labels;
    }

    public Map<String, Object> getVariables() {
        return this.variables;
    }

    public @Valid @NotEmpty List<Task> getTasks() {
        return this.tasks;
    }

    public @Valid List<Task> getErrors() {
        return this.errors;
    }

    public @Valid List<Listener> getListeners() {
        return this.listeners;
    }

    public @Valid List<AbstractTrigger> getTriggers() {
        return this.triggers;
    }

    public List<TaskDefault> getTaskDefaults() {
        return this.taskDefaults;
    }

    public @NotNull boolean isDisabled() {
        return this.disabled;
    }

    public @NotNull boolean isDeleted() {
        return this.deleted;
    }

    public boolean equals(final Object o) {
        if (o == this) return true;
        if (!(o instanceof Flow)) return false;
        final Flow other = (Flow) o;
        if (!other.canEqual((Object) this)) return false;
        final Object this$id = this.getId();
        final Object other$id = other.getId();
        if (this$id == null ? other$id != null : !this$id.equals(other$id)) return false;
        final Object this$namespace = this.getNamespace();
        final Object other$namespace = other.getNamespace();
        if (this$namespace == null ? other$namespace != null : !this$namespace.equals(other$namespace))
            return false;
        final Object this$revision = this.getRevision();
        final Object other$revision = other.getRevision();
        if (this$revision == null ? other$revision != null : !this$revision.equals(other$revision))
            return false;
        final Object this$description = this.getDescription();
        final Object other$description = other.getDescription();
        if (this$description == null ? other$description != null : !this$description.equals(other$description))
            return false;
        final Object this$labels = this.getLabels();
        final Object other$labels = other.getLabels();
        if (this$labels == null ? other$labels != null : !this$labels.equals(other$labels))
            return false;
        final Object this$variables = this.getVariables();
        final Object other$variables = other.getVariables();
        if (this$variables == null ? other$variables != null : !this$variables.equals(other$variables))
            return false;
        final Object this$tasks = this.getTasks();
        final Object other$tasks = other.getTasks();
        if (this$tasks == null ? other$tasks != null : !this$tasks.equals(other$tasks))
            return false;
        final Object this$errors = this.getErrors();
        final Object other$errors = other.getErrors();
        if (this$errors == null ? other$errors != null : !this$errors.equals(other$errors))
            return false;
        final Object this$listeners = this.getListeners();
        final Object other$listeners = other.getListeners();
        if (this$listeners == null ? other$listeners != null : !this$listeners.equals(other$listeners))
            return false;
        final Object this$triggers = this.getTriggers();
        final Object other$triggers = other.getTriggers();
        if (this$triggers == null ? other$triggers != null : !this$triggers.equals(other$triggers))
            return false;
        final Object this$taskDefaults = this.getTaskDefaults();
        final Object other$taskDefaults = other.getTaskDefaults();
        if (this$taskDefaults == null ? other$taskDefaults != null : !this$taskDefaults.equals(other$taskDefaults))
            return false;
        if (this.isDisabled() != other.isDisabled()) return false;
        if (this.isDeleted() != other.isDeleted()) return false;
        return true;
    }

    protected boolean canEqual(final Object other) {
        return other instanceof Flow;
    }

    public int hashCode() {
        final int PRIME = 59;
        int result = 1;
        final Object $id = this.getId();
        result = result * PRIME + ($id == null ? 43 : $id.hashCode());
        final Object $namespace = this.getNamespace();
        result = result * PRIME + ($namespace == null ? 43 : $namespace.hashCode());
        final Object $revision = this.getRevision();
        result = result * PRIME + ($revision == null ? 43 : $revision.hashCode());
        final Object $description = this.getDescription();
        result = result * PRIME + ($description == null ? 43 : $description.hashCode());
        final Object $labels = this.getLabels();
        result = result * PRIME + ($labels == null ? 43 : $labels.hashCode());
        final Object $variables = this.getVariables();
        result = result * PRIME + ($variables == null ? 43 : $variables.hashCode());
        final Object $tasks = this.getTasks();
        result = result * PRIME + ($tasks == null ? 43 : $tasks.hashCode());
        final Object $errors = this.getErrors();
        result = result * PRIME + ($errors == null ? 43 : $errors.hashCode());
        final Object $listeners = this.getListeners();
        result = result * PRIME + ($listeners == null ? 43 : $listeners.hashCode());
        final Object $triggers = this.getTriggers();
        result = result * PRIME + ($triggers == null ? 43 : $triggers.hashCode());
        final Object $taskDefaults = this.getTaskDefaults();
        result = result * PRIME + ($taskDefaults == null ? 43 : $taskDefaults.hashCode());
        result = result * PRIME + (this.isDisabled() ? 79 : 97);
        result = result * PRIME + (this.isDeleted() ? 79 : 97);
        return result;
    }

    public String toString() {
        return "Flow(id=" + this.getId() + ", namespace=" + this.getNamespace() + ", revision=" + this.getRevision() + ", description=" + this.getDescription() + ", labels=" + this.getLabels() + ", variables=" + this.getVariables() + ", tasks=" + this.getTasks() + ", errors=" + this.getErrors() + ", listeners=" + this.getListeners() + ", triggers=" + this.getTriggers() + ", taskDefaults=" + this.getTaskDefaults() + ", disabled=" + this.isDisabled() + ", deleted=" + this.isDeleted() + ")";
    }

    public Flow withRevision(@Min(value = 1) Integer revision) {
        return this.revision == revision ? this : new Flow(this.id, this.namespace, revision, this.description, this.labels, this.variables, this.tasks, this.errors, this.listeners, this.triggers, this.taskDefaults, this.disabled, this.deleted);
    }

    public FlowBuilder<?, ?> toBuilder() {
        return new FlowBuilderImpl().$fillValuesFrom(this);
    }

    public static abstract class FlowBuilder<C extends Flow, B extends FlowBuilder<C, B>> {
        private @NotNull
        @NotBlank
        @Pattern(regexp = "[a-zA-Z0-9._-]+") String id;
        private @NotNull
        @Pattern(regexp = "[a-z0-9._-]+") String namespace;
        private @Min(value = 1) Integer revision;
        private String description;
        private List<Label> labels;
        private Map<String, Object> variables;
        private @Valid
        @NotEmpty List<Task> tasks;
        private @Valid List<Task> errors;
        private @Valid List<Listener> listeners;
        private @Valid List<AbstractTrigger> triggers;
        private List<TaskDefault> taskDefaults;
        private @NotNull boolean disabled$value;
        private boolean disabled$set;
        private @NotNull boolean deleted$value;
        private boolean deleted$set;

        private static void $fillValuesFromInstanceIntoBuilder(Flow instance, FlowBuilder<?, ?> b) {
            b.id(instance.id);
            b.namespace(instance.namespace);
            b.revision(instance.revision);
            b.description(instance.description);
            b.labels(instance.labels);
            b.variables(instance.variables);
            b.tasks(instance.tasks);
            b.errors(instance.errors);
            b.listeners(instance.listeners);
            b.triggers(instance.triggers);
            b.taskDefaults(instance.taskDefaults);
            b.disabled(instance.disabled);
            b.deleted(instance.deleted);
        }

        public B id(@NotNull @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]+") String id) {
            this.id = id;
            return self();
        }

        public B namespace(@NotNull @Pattern(regexp = "[a-z0-9._-]+") String namespace) {
            this.namespace = namespace;
            return self();
        }

        public B revision(@Min(value = 1) Integer revision) {
            this.revision = revision;
            return self();
        }

        public B description(String description) {
            this.description = description;
            return self();
        }

        public B labels(List<Label> labels) {
            this.labels = labels;
            return self();
        }

        public B variables(Map<String, Object> variables) {
            this.variables = variables;
            return self();
        }

        public B tasks(@Valid @NotEmpty List<Task> tasks) {
            this.tasks = tasks;
            return self();
        }

        public B errors(@Valid List<Task> errors) {
            this.errors = errors;
            return self();
        }

        public B listeners(@Valid List<Listener> listeners) {
            this.listeners = listeners;
            return self();
        }

        public B triggers(@Valid List<AbstractTrigger> triggers) {
            this.triggers = triggers;
            return self();
        }

        public B taskDefaults(List<TaskDefault> taskDefaults) {
            this.taskDefaults = taskDefaults;
            return self();
        }

        public B disabled(@NotNull boolean disabled) {
            this.disabled$value = disabled;
            this.disabled$set = true;
            return self();
        }

        public B deleted(@NotNull boolean deleted) {
            this.deleted$value = deleted;
            this.deleted$set = true;
            return self();
        }

        protected B $fillValuesFrom(C instance) {
            FlowBuilder.$fillValuesFromInstanceIntoBuilder(instance, this);
            return self();
        }

        protected abstract B self();

        public abstract C build();

        public String toString() {
            return "Flow.FlowBuilder(id=" + this.id + ", namespace=" + this.namespace + ", revision=" + this.revision + ", description=" + this.description + ", labels=" + this.labels + ", variables=" + this.variables + ", tasks=" + this.tasks + ", errors=" + this.errors + ", listeners=" + this.listeners + ", triggers=" + this.triggers + ", taskDefaults=" + this.taskDefaults + ", disabled$value=" + this.disabled$value + ", deleted$value=" + this.deleted$value + ")";
        }
    }

    private static final class FlowBuilderImpl extends FlowBuilder<Flow, FlowBuilderImpl> {
        private FlowBuilderImpl() {
        }

        protected FlowBuilderImpl self() {
            return this;
        }

        public Flow build() {
            return new Flow(this);
        }
    }
}
