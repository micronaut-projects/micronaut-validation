package io.micronaut.docs.validation.path.model;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Introspected
public final class State {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(State.class);
    @NotNull
    private final
    Type current;

    @Valid
    private final
    List<History> histories;

    public State() {
        this.current = Type.CREATED;
        this.histories = new ArrayList<>();
        this.histories.add(new History(this.current, Instant.now()));
    }

    public State(Type type) {
        this.current = type;
        this.histories = new ArrayList<>();
        this.histories.add(new History(this.current, Instant.now()));
    }

    public State(State state, Type type) {
        this.current = type;
        this.histories = state.histories;
        this.histories.add(new History(this.current, Instant.now()));
    }

    public State(Type state, State actual) {
        this.current = state;
        this.histories = new ArrayList<>(actual.histories);
        this.histories.add(new History(this.current, Instant.now()));
    }

    public static State of(Type state, List<History> histories) {
        State result = new State(state);

        result.histories.removeIf(history -> true);
        result.histories.addAll(histories);

        return result;
    }

    public State withState(Type state) {
        if (this.current == state) {
            log.warn("Can't change state, already " + current);
            return this;
        }

        return new State(state, this);
    }

    public Duration getDuration() {
        return Duration.between(
            this.histories.get(0).getDate(),
            this.histories.size() > 1 ? this.histories.get(this.histories.size() - 1).getDate() : Instant.now()
        );
    }

    public Instant getStartDate() {
        return this.histories.get(0).getDate();
    }

    public Optional<Instant> getEndDate() {
        if (!this.isTerminated() && !this.isPaused()) {
            return Optional.empty();
        }

        return Optional.of(this.histories.get(this.histories.size() - 1).getDate());
    }

    public Instant maxDate() {
        if (this.histories.size() == 0) {
            return Instant.now();
        }

        return this.histories.get(this.histories.size() - 1).getDate();
    }

    public boolean isTerminated() {
        return this.current.isTerminated();
    }

    public boolean isRunning() {
        return this.current.isRunning();
    }

    public boolean isCreated() {
        return this.current.isCreated();
    }

    public static Type[] runningTypes() {
        return Arrays.stream(Type.values())
            .filter(type -> type.isRunning() || type.isCreated())
            .toArray(Type[]::new);
    }

    public boolean isFailed() {
        return this.current.isFailed();
    }

    public boolean isPaused() {
        return this.current.isPaused();
    }

    public boolean isRestartable() {
        return this.current.isFailed() || this.isPaused();
    }

    public @NotNull Type getCurrent() {
        return this.current;
    }

    public @Valid List<History> getHistories() {
        return this.histories;
    }

    public String toString() {
        return "State(current=" + this.getCurrent() + ", histories=" + this.getHistories() + ")";
    }


    @Introspected
    public enum Type {
        CREATED,
        RUNNING,
        PAUSED,
        RESTARTED,
        KILLING,
        SUCCESS,
        WARNING,
        FAILED,
        KILLED;

        public boolean isTerminated() {
            return this == Type.FAILED || this == Type.WARNING || this == Type.SUCCESS || this == Type.KILLED;
        }

        public boolean isCreated() {
            return this == Type.CREATED || this == Type.RESTARTED;
        }

        public boolean isRunning() {
            return this == Type.RUNNING || this == Type.KILLING;
        }

        public boolean isFailed() {
            return this == Type.FAILED;
        }

        public boolean isPaused() {
            return this == Type.PAUSED;
        }
    }

    public static final class History {
        @NotNull
        private final
        Type state;

        @NotNull
        private final
        Instant date;

        public History(@NotNull Type state, @NotNull Instant date) {
            this.state = state;
            this.date = date;
        }

        public @NotNull Type getState() {
            return this.state;
        }

        public @NotNull Instant getDate() {
            return this.date;
        }

        public String toString() {
            return "State.History(state=" + this.getState() + ", date=" + this.getDate() + ")";
        }
    }
}
