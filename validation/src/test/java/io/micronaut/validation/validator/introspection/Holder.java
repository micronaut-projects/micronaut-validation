package io.micronaut.validation.validator.introspection;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * A nested type shaped like the TCK container element models: a private field read by an annotated getter.
 */
public class Holder {

    @Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD}, visibility = Introspected.Visibility.ANY)
    public static class WithList {

        private List<String> strings = List.of("");

        public List<@NotBlank String> getStrings() {
            return strings;
        }
    }

    public static class NotIntrospected {

        private List<String> strings = List.of("");

        public List<@NotBlank String> getStrings() {
            return strings;
        }
    }

    public static class NotIntrospectedField {

        private List<@NotBlank String> strings = List.of("");

        public List<String> getStrings() {
            return strings;
        }
    }
}
