package io.micronaut.validation.validator.introspection;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotNull;

@Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD}, visibility = Introspected.Visibility.ANY)
public class Address {

    @NotNull
    private String street;

    @NotNull(groups = Basic.class)
    private String zip;

    public String getStreet() {
        return street;
    }

    public String getZip() {
        return zip;
    }
}
