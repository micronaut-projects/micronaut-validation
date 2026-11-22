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
package io.micronaut.validation.validator;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.GenericPlaceholder;
import io.micronaut.core.type.WildcardArgument;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArgumentStructureTest {
    @Test
    void mergingPreservesWildcardBoundsAndNestedStructure() {
        Argument<?> wildcard = Argument.ofWildcard(Object.class, "T", AnnotationMetadata.EMPTY_METADATA,
            Argument.ZERO_ARGUMENTS, new Argument<?>[]{Argument.OBJECT_ARGUMENT}, new Argument<?>[]{Argument.STRING});
        Argument<?> list = Argument.of(List.class, "items", AnnotationMetadata.EMPTY_METADATA, wildcard);
        Argument<?> merged = ExecutableHierarchy.mergeArgument(List.of(list, list));
        assertTrue(list.equalsStructure(merged));
        WildcardArgument<?> actual = assertInstanceOf(WildcardArgument.class, merged.getTypeParameters()[0]);
        assertEquals(List.of(Argument.STRING), actual.getLowerBounds());
    }

    @Test
    void mergingPreservesResolvedVariablesAndIntersectionBounds() {
        Argument<?> variable = Argument.ofResolvedTypeVariable(String.class, "value", "T", AnnotationMetadata.EMPTY_METADATA,
            Argument.ZERO_ARGUMENTS, new Argument<?>[]{Argument.of(CharSequence.class), Argument.of(Serializable.class)});
        GenericPlaceholder<?> merged = assertInstanceOf(GenericPlaceholder.class, ExecutableHierarchy.mergeArgument(List.of(variable)));
        assertTrue(merged.isResolved());
        assertEquals(2, merged.getBounds().size());
        assertTrue(variable.equalsStructure(merged));
    }

    @Test
    void mergingPreservesRawTypes() {
        Argument<?> raw = Argument.ofRawType(List.class, "values", AnnotationMetadata.EMPTY_METADATA,
            new Argument<?>[]{Argument.ofTypeVariable(Object.class, "E", "E")});
        Argument<?> merged = ExecutableHierarchy.mergeArgument(List.of(raw));
        assertTrue(merged.isRawType());
        assertTrue(raw.equalsStructure(merged));
    }
}
