package io.micronaut.validation.validator.introspection;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Size;

@Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD}, visibility = Introspected.Visibility.ANY)
public class Child extends Parent {

    @Size(max = 3)
    private String nickname = "also too long";

    public String getNickname() {
        return nickname;
    }
}
