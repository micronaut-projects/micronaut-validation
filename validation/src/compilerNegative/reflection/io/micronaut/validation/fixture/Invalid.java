package io.micronaut.validation.fixture;
class ForbiddenReflection {
    Object read(Class<?> type) throws Exception { return type.getDeclaredConstructor().newInstance(); }
}
