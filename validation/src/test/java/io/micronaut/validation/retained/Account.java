package io.micronaut.validation.retained;

import io.micronaut.core.annotation.ReflectiveAccess;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class Account {

    @MinimumLength(min = 8)
    @ReflectiveAccess
    private String username;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
