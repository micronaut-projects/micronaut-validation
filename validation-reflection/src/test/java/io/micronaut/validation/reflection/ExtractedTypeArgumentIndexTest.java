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
package io.micronaut.validation.reflection;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.type.Argument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a type's classes answers which of its type arguments carries the extracted one as its generated
 * introspection does: a type argument the type binds is none of its own.
 */
class ExtractedTypeArgumentIndexTest {

    @Test
    void reflectionAgreesWithTheIntrospection() {
        List<Argument<?>> generated = BeanIntrospection.getIntrospection(Keyed.class).getTypeArguments(Pair.class);
        // the introspection: the first argument bound to String, the second passed on from the first variable
        assertFalse(generated.get(0).isUnresolvedTypeVariable());
        assertTrue(generated.get(1).isUnresolvedTypeVariable());

        assertNull(ReflectionContainerTypeArguments.extractedTypeArgumentIndex(Keyed.class, Pair.class, 0));
        assertEquals(0, ReflectionContainerTypeArguments.extractedTypeArgumentIndex(Keyed.class, Pair.class, 1));
    }

    public static class Pair<K, V> {
    }

    @Introspected
    public static class Keyed<T> extends Pair<String, T> {
    }
}
