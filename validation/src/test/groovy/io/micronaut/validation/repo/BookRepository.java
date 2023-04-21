package io.micronaut.validation.repo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

@RepoDef
public interface BookRepository extends MyRepository<@Valid Book, @Min(5) Long> {
}
