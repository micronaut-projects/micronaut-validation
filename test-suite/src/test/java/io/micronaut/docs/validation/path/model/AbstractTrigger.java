package io.micronaut.docs.validation.path.model;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

@Introspected
abstract public class AbstractTrigger {
    @NotNull
    @NotBlank
    @Pattern(regexp = "[a-zA-Z0-9_-]+")
    protected String id;

    @NotNull
    @NotBlank
    @Pattern(regexp = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*")
    protected String type;

    private String description;

    @Valid
    private List<Condition> conditions;

    @NotNull
    private boolean disabled = false;

    @Valid
    private WorkerGroup workerGroup;

    public AbstractTrigger() {
    }

    protected AbstractTrigger(AbstractTriggerBuilder<?, ?> b) {
        this.id = b.id;
        this.type = b.type;
        this.description = b.description;
        this.conditions = b.conditions;
        if (b.disabled$set) {
            this.disabled = b.disabled$value;
        } else {
            this.disabled = $default$disabled();
        }
        this.workerGroup = b.workerGroup;
    }

    private static boolean $default$disabled() {
        return false;
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

    public @Valid List<Condition> getConditions() {
        return this.conditions;
    }

    public @NotNull boolean isDisabled() {
        return this.disabled;
    }

    public @Valid WorkerGroup getWorkerGroup() {
        return this.workerGroup;
    }

    public static abstract class AbstractTriggerBuilder<C extends AbstractTrigger, B extends AbstractTriggerBuilder<C, B>> {
        private @NotNull
        @NotBlank
        @Pattern(regexp = "[a-zA-Z0-9_-]+") String id;
        private @NotNull
        @NotBlank
        @Pattern(regexp = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*") String type;
        private String description;
        private @Valid List<Condition> conditions;
        private @NotNull boolean disabled$value;
        private boolean disabled$set;
        private @Valid WorkerGroup workerGroup;

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

        public B conditions(@Valid List<Condition> conditions) {
            this.conditions = conditions;
            return self();
        }

        public B disabled(@NotNull boolean disabled) {
            this.disabled$value = disabled;
            this.disabled$set = true;
            return self();
        }

        public B workerGroup(@Valid WorkerGroup workerGroup) {
            this.workerGroup = workerGroup;
            return self();
        }

        protected abstract B self();

        public abstract C build();

        public String toString() {
            return "AbstractTrigger.AbstractTriggerBuilder(id=" + this.id + ", type=" + this.type + ", description=" + this.description + ", conditions=" + this.conditions + ", disabled$value=" + this.disabled$value + ", workerGroup=" + this.workerGroup + ")";
        }
    }
}
