package io.micronaut.docs.validation.path.model;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Introspected
public abstract class Condition {
    @NotNull
    @Pattern(regexp = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*")
    protected String type;

    public Condition(@NotNull @Pattern(regexp = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*") String type) {
        this.type = type;
    }

    public Condition() {
    }

    public @NotNull @Pattern(regexp = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*") String getType() {
        return this.type;
    }

    public static abstract class ConditionBuilder<C extends Condition, B extends ConditionBuilder<C, B>> {
        private @NotNull
        @Pattern(regexp = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*") String type;

        public B type(@NotNull @Pattern(regexp = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*") String type) {
            this.type = type;
            return self();
        }

        protected abstract B self();

        public abstract C build();

        public String toString() {
            return "Condition.ConditionBuilder(type=" + this.type + ")";
        }
    }
}
