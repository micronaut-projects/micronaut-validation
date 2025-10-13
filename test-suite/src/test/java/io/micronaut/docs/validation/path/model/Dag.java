package io.micronaut.docs.validation.path.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.docs.validation.path.validations.DagTaskValidation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@DagTaskValidation
public class Dag extends Task {
    @NotNull
    private Integer concurrent = 0;

    @NotEmpty
    @Valid
    private List<DagTask> dagTasks;

    @Valid
    protected List<Task> errors;

    public Dag() {
    }

    protected Dag(DagBuilder<?, ?> b) {
        super(b);
        if (b.concurrent$set) {
            this.concurrent = b.concurrent$value;
        } else {
            this.concurrent = $default$concurrent();
        }
        this.dagTasks = b.dagTasks;
        this.errors = b.errors;
    }

    private static Integer $default$concurrent() {
        return 0;
    }

    public static DagBuilder<?, ?> builder() {
        return new DagBuilderImpl();
    }

    public List<String> dagCheckNotExistTask(List<DagTask> taskDepends) {
        List<String> dependenciesIds = taskDepends
            .stream()
            .map(DagTask::getDependsOn)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .toList();

        List<String> tasksIds = taskDepends
            .stream()
            .map(taskDepend -> taskDepend.getTask().getId())
            .toList();

        return dependenciesIds.stream()
            .filter(dependencyId -> !tasksIds.contains(dependencyId))
            .collect(Collectors.toList());
    }

    public ArrayList<String> dagCheckCyclicDependencies(List<DagTask> taskDepends) {
        ArrayList<String> cyclicDependency = new ArrayList<>();
        taskDepends.forEach(taskDepend -> {
            if (taskDepend.getDependsOn() != null) {
                List<String> nestedDependencies = this.nestedDependencies(taskDepend, taskDepends, new ArrayList<>());
                if (nestedDependencies.contains(taskDepend.getTask().getId())) {
                    cyclicDependency.add(taskDepend.getTask().getId());
                }
            }
        });

        return cyclicDependency;
    }

    private ArrayList<String> nestedDependencies(DagTask taskDepend, List<DagTask> tasks, List<String> visited) {
        final ArrayList<String> localVisited = new ArrayList<>(visited);
        if (taskDepend.getDependsOn() != null) {
            taskDepend.getDependsOn()
                .stream()
                .filter(depend -> !localVisited.contains(depend))
                .forEach(depend -> {
                    localVisited.add(depend);
                    Optional<DagTask> task = tasks
                        .stream()
                        .filter(t -> t.getTask().getId().equals(depend))
                        .findFirst();

                    if (task.isPresent()) {
                        localVisited.addAll(this.nestedDependencies(task.get(), tasks, localVisited));
                    }
                });
        }
        return localVisited;
    }

    public @NotNull Integer getConcurrent() {
        return this.concurrent;
    }

    public @NotEmpty @Valid List<DagTask> getDagTasks() {
        return this.dagTasks;
    }

    public @Valid List<Task> getErrors() {
        return this.errors;
    }

    public String toString() {
        return "Dag(concurrent=" + this.getConcurrent() + ", dagTasks=" + this.getDagTasks() + ", errors=" + this.getErrors() + ")";
    }

    @Introspected
    public static class DagTask {
        @NotNull
        private Task task;

        private List<String> dependsOn;

        public DagTask() {
        }

        protected DagTask(DagTaskBuilder<?, ?> b) {
            this.task = b.task;
            this.dependsOn = b.dependsOn;
        }

        public static DagTaskBuilder<?, ?> builder() {
            return new DagTaskBuilderImpl();
        }

        public @NotNull Task getTask() {
            return this.task;
        }

        public List<String> getDependsOn() {
            return this.dependsOn;
        }

        public String toString() {
            return "Dag.DagTask(task=" + this.getTask() + ", dependsOn=" + this.getDependsOn() + ")";
        }

        public static abstract class DagTaskBuilder<C extends DagTask, B extends DagTaskBuilder<C, B>> {
            private @NotNull Task task;
            private List<String> dependsOn;

            public B task(@NotNull Task task) {
                this.task = task;
                return self();
            }

            public B dependsOn(List<String> dependsOn) {
                this.dependsOn = dependsOn;
                return self();
            }

            protected abstract B self();

            public abstract C build();

            public String toString() {
                return "Dag.DagTask.DagTaskBuilder(task=" + this.task + ", dependsOn=" + this.dependsOn + ")";
            }
        }

        private static final class DagTaskBuilderImpl extends DagTaskBuilder<DagTask, DagTaskBuilderImpl> {
            private DagTaskBuilderImpl() {
            }

            protected DagTaskBuilderImpl self() {
                return this;
            }

            public DagTask build() {
                return new DagTask(this);
            }
        }
    }

    public static abstract class DagBuilder<C extends Dag, B extends DagBuilder<C, B>> extends TaskBuilder<C, B> {
        private @NotNull Integer concurrent$value;
        private boolean concurrent$set;
        private @NotEmpty
        @Valid List<DagTask> dagTasks;
        private @Valid List<Task> errors;

        public B concurrent(@NotNull Integer concurrent) {
            this.concurrent$value = concurrent;
            this.concurrent$set = true;
            return self();
        }

        public B dagTasks(@NotEmpty @Valid List<DagTask> dagTasks) {
            this.dagTasks = dagTasks;
            return self();
        }

        public B errors(@Valid List<Task> errors) {
            this.errors = errors;
            return self();
        }

        protected abstract B self();

        public abstract C build();

        public String toString() {
            return "Dag.DagBuilder(super=" + super.toString() + ", concurrent$value=" + this.concurrent$value + ", dagTasks=" + this.dagTasks + ", errors=" + this.errors + ")";
        }
    }

    private static final class DagBuilderImpl extends DagBuilder<Dag, DagBuilderImpl> {
        private DagBuilderImpl() {
        }

        protected DagBuilderImpl self() {
            return this;
        }

        public Dag build() {
            return new Dag(this);
        }
    }
}
