package io.micronaut.validation.fixture;

import io.micronaut.core.annotation.AnnotationMetadata;
import jakarta.validation.constraints.NotNull;

final class Invalid {
    NotNull forbidden() {
        return AnnotationMetadata.EMPTY_METADATA.synthesize(NotNull.class);
    }
}
