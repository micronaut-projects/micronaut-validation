/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.validation.jakarta;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;
import javafx.beans.property.ListProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A JavaFX list property cascades into its elements although an extractor unwraps the property by default.
 */
class JavaFxCascadeTest {

    static final class Child {
        @NotNull
        private final String name;

        Child(String name) {
            this.name = name;
        }
    }

    static final class LegacyCascade {
        @Valid
        private final ListProperty<Child> children = new SimpleListProperty<>(FXCollections.observableArrayList(new Child(null)));
    }

    static final class TypeArgumentCascade {
        private final ListProperty<@Valid Child> children = new SimpleListProperty<>(FXCollections.observableArrayList(new Child(null)));
    }

    private static <T> Set<String> paths(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void validOnAListPropertyCascadesIntoTheElements() {
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var violations = validatorFactory.getValidator().validate(new LegacyCascade());
            assertEquals(Set.of("children[0].name"), paths(violations), violations.toString());
        }
    }

    @Test
    void validOnTheTypeArgumentOfAListPropertyCascadesIntoTheElements() {
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var violations = validatorFactory.getValidator().validate(new TypeArgumentCascade());
            assertEquals(Set.of("children[0].name"), paths(violations), violations.toString());
        }
    }
}
