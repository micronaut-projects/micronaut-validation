package io.micronaut.validation.fixture;

import io.micronaut.core.reflect.ClassUtils;

final class Invalid {
    boolean forbidden(String name) {
        return ClassUtils.forName(name, Invalid.class.getClassLoader()).isPresent();
    }
}
