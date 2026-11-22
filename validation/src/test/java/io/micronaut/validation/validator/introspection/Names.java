package io.micronaut.validation.validator.introspection;

import io.micronaut.context.annotation.Executable;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;

import java.util.List;

/**
 * Introspected the way the TCK archives are: fields and methods, any visibility.
 */
@Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD}, visibility = Introspected.Visibility.ANY)
public class Names {

    private List<String> strings = List.of("", "ok");

    public List<@NotBlank String> getStrings() {
        return strings;
    }

    public void setStrings(List<String> strings) {
        this.strings = strings;
    }

    @ConvertGroup(from = Default.class, to = Names.class)
    public List<String> retrieve() {
        return strings;
    }

    @Executable
    @Flag.List({@Flag(message = "first"), @Flag(message = "second")})
    public void setNames(String first, CharSequence last) {
    }

    @Executable
    @Flag(message = "single")
    public void setOne(String first, CharSequence last) {
    }
}
