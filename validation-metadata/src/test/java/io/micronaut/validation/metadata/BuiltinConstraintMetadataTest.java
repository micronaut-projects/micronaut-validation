package io.micronaut.validation.metadata;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.validation.validator.metadata.GeneratedAnnotationFactories;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinConstraintMetadataTest {
    // This source set deliberately has no validation annotation processor. The runtime artifact,
    // rather than the application or a previous validation, must supply this annotation contract.
    @Size(max = 5)
    private String value;

    @Test
    void standardAnnotationDefaultsAreAvailableBeforeAnyApplicationModelIsValidated() throws Exception {
        var annotation = GeneratedAnnotationFactories.create(Size.class,
            new AnnotationValue<>(Size.class.getName(), Map.of("max", 5)));
        var expected = getClass().getDeclaredField("value").getAnnotation(Size.class);
        assertEquals(0, annotation.min());
        assertEquals(5, annotation.max());
        assertEquals(expected, annotation);
        assertEquals(annotation, expected);
        assertEquals(expected.hashCode(), annotation.hashCode());
        assertEquals(0, annotation.groups().length);
        assertEquals(0, annotation.payload().length);
        assertFalse(GeneratedAnnotationFactories.annotationMembers(Size.class).isEmpty());
    }
}
