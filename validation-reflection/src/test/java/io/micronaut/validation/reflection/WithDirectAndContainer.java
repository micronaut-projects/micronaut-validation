package io.micronaut.validation.reflection;

import io.micronaut.core.annotation.Introspected;

/**
 * A bean carrying a constraint composed both directly and inside its container.
 */
@Introspected
public class WithDirectAndContainer {
    @DirectAndContainer
    private String zip = "abc";

    public String getZip() {
        return zip;
    }
}
