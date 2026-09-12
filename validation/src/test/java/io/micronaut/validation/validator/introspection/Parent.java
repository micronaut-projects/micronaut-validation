package io.micronaut.validation.validator.introspection;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.GroupSequence;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;

/**
 * Redefines its default group sequence: its constraints follow it, the ones of its sub types do not.
 */
@GroupSequence({Minimal.class, Parent.class})
@Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD}, visibility = Introspected.Visibility.ANY)
public class Parent {

    @Max(value = 10, groups = Minimal.class)
    private int size = 20;

    @Size(max = 3)
    private String name = "too long";

    public int getSize() {
        return size;
    }

    public String getName() {
        return name;
    }
}
