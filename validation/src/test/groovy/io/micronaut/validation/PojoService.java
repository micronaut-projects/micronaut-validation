package io.micronaut.validation;

import jakarta.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@Singleton
public class PojoService {
    public void validate(@NotNull @Valid Pojo search) {

    }

    public void validateIterable(@Valid Iterable<Pojo> search) {

    }

    public boolean validateIterableWithoutCascade(Iterable<Pojo> iterable, @NotNull @Valid Pojo search) {
        return true;
    }

    public boolean validateRawIterableWithoutCascade(Iterable iterable, @NotNull @Valid Pojo search) {
        return true;
    }

    public boolean validateRawIterableWithoutCascadeCustomIterable(Session iterable, @NotNull @Valid Pojo search) {
        return true;
    }

    public interface Session extends Iterable {}
}

