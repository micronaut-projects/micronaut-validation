package io.micronaut.validation.validator.introspection;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.Valid;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Container elements cascaded and converted, declared on a field, the way the TCK models declare them.
 */
@Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD}, visibility = Introspected.Visibility.ANY)
public class User {

    private final List<@Valid @ConvertGroup(from = Default.class, to = Basic.class) Address> addresses = List.of(new Address());

    private final Map<String, List<Optional<@jakarta.validation.constraints.Size(min = 3) String>>> nested = Map.of("k", List.of(Optional.of("ab")));

    public List<Address> getAddresses() {
        return addresses;
    }

    public Map<String, List<Optional<String>>> getNested() {
        return nested;
    }
}
