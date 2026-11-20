package io.micronaut.validation.fixture;
@org.jspecify.annotations.NullMarked
class ForbiddenNullness {
    int length(@org.jspecify.annotations.Nullable String value) { return value.length(); }
}
