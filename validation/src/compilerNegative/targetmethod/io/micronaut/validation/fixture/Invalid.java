package io.micronaut.validation.fixture;

import io.micronaut.inject.ExecutableMethod;

import java.lang.reflect.Method;

final class Invalid {
    Method forbidden(ExecutableMethod<?, ?> method) {
        return method.getTargetMethod();
    }
}
