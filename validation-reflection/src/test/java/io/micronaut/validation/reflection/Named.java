package io.micronaut.validation.reflection;

import jakarta.validation.constraints.NotNull;

/**
 * A constraint declared by an interface is in the group of the interface.
 */
public interface Named {

    @NotNull
    String getLastName();
}
