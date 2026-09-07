package io.micronaut.validation.reflection;

import io.micronaut.core.annotation.Introspected;

@Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD}, visibility = Introspected.Visibility.ANY)
public class NamedPerson implements Named {

    @Override
    public String getLastName() {
        return null;
    }
}
