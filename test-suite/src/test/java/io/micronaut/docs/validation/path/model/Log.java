package io.micronaut.docs.validation.path.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.event.Level;

public class Log extends Task {
    @NotNull
    @NotBlank
    private Object message;

    private Level level = Level.INFO;

    public Log() {
    }

    protected Log(LogBuilder<?, ?> b) {
        super(b);
        this.message = b.message;
        if (b.level$set) {
            this.level = b.level$value;
        } else {
            this.level = $default$level();
        }
    }

    private static Level $default$level() {
        return Level.INFO;
    }

    public static LogBuilder<?, ?> builder() {
        return new LogBuilderImpl();
    }

    public @NotNull @NotBlank Object getMessage() {
        return this.message;
    }

    public Level getLevel() {
        return this.level;
    }

    public boolean equals(final Object o) {
        if (o == this) return true;
        if (!(o instanceof Log)) return false;
        final Log other = (Log) o;
        if (!other.canEqual((Object) this)) return false;
        final Object this$message = this.getMessage();
        final Object other$message = other.getMessage();
        if (this$message == null ? other$message != null : !this$message.equals(other$message))
            return false;
        final Object this$level = this.getLevel();
        final Object other$level = other.getLevel();
        if (this$level == null ? other$level != null : !this$level.equals(other$level))
            return false;
        return true;
    }

    protected boolean canEqual(final Object other) {
        return other instanceof Log;
    }

    public int hashCode() {
        final int PRIME = 59;
        int result = 1;
        final Object $message = this.getMessage();
        result = result * PRIME + ($message == null ? 43 : $message.hashCode());
        final Object $level = this.getLevel();
        result = result * PRIME + ($level == null ? 43 : $level.hashCode());
        return result;
    }

    public String toString() {
        return "Log(message=" + this.getMessage() + ", level=" + this.getLevel() + ")";
    }

    public static abstract class LogBuilder<C extends Log, B extends LogBuilder<C, B>> extends TaskBuilder<C, B> {
        private @NotNull
        @NotBlank Object message;
        private Level level$value;
        private boolean level$set;

        public B message(@NotNull @NotBlank Object message) {
            this.message = message;
            return self();
        }

        public B level(Level level) {
            this.level$value = level;
            this.level$set = true;
            return self();
        }

        protected abstract B self();

        public abstract C build();

        public String toString() {
            return "Log.LogBuilder(super=" + super.toString() + ", message=" + this.message + ", level$value=" + this.level$value + ")";
        }
    }

    private static final class LogBuilderImpl extends LogBuilder<Log, LogBuilderImpl> {
        private LogBuilderImpl() {
        }

        protected LogBuilderImpl self() {
            return this;
        }

        public Log build() {
            return new Log(this);
        }
    }
}


