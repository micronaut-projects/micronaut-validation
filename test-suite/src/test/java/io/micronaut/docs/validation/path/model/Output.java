package io.micronaut.docs.validation.path.model;

import java.util.Optional;

public interface Output {
    default Optional<State.Type> finalState() {
        return Optional.empty();
    }
}
