package io.micronaut.validation.fixture;

import io.micronaut.core.reflect.InstantiationUtils;

final class Invalid {
    Object forbidden(Class<?> type) {
        return InstantiationUtils.instantiate(type);
    }
}
