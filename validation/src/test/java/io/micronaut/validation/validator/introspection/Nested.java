package io.micronaut.validation.validator.introspection;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Nested container elements, not introspected: the bridge describes them.
 */
public class Nested {

    private final List<@Size(min = 3) String> one = List.of("ab");

    private final List<Optional<@Size(min = 3) String>> two = List.of(Optional.of("ab"));

    private final Map<String, @Size(min = 3) String> mapValue = Map.of("k", "ab");

    private final Map<String, List<Optional<@Size(min = 3) String>>> three = Map.of("k", List.of(Optional.of("ab")));

    public List<String> getOne() {
        return one;
    }

    public List<Optional<String>> getTwo() {
        return two;
    }

    public Map<String, String> getMapValue() {
        return mapValue;
    }

    public Map<String, List<Optional<String>>> getThree() {
        return three;
    }
}
