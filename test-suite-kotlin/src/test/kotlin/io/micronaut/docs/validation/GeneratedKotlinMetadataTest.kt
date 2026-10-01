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
package io.micronaut.docs.validation

import io.micronaut.core.annotation.AnnotationValue
import io.micronaut.core.annotation.Introspected
import io.micronaut.core.annotation.ReflectiveAccess
import io.micronaut.validation.validator.Validator
import io.micronaut.validation.validator.metadata.GeneratedAnnotationFactories
import jakarta.validation.Constraint
import jakarta.validation.Payload
import jakarta.validation.constraints.NotNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

class GeneratedKotlinMetadataTest {
    @Test
    fun concreteAnnotationsKeepDefaultsAndDefensiveArrays() {
        val generated = GeneratedAnnotationFactories.create(Contract::class.java, AnnotationValue<Contract>(Contract::class.java.name))
        val reference = Bean::class.java.getDeclaredField("value").getAnnotation(Contract::class.java)
        assertEquals(reference, generated)
        assertEquals(generated, reference)
        assertEquals(reference.hashCode(), generated.hashCode())
        assertEquals("", generated.empty)
        val changed = generated.numbers
        changed[0] = 99
        assertArrayEquals(intArrayOf(1, 2), generated.numbers)
    }

    @Test
    fun fieldSpecificPermissionAndNestedArgumentsWorkThroughKsp() {
        val violations = Validator.getInstance().validate(PrivateBean(null, listOf(null)))
        assertEquals(2, violations.size)
    }

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FIELD)
    @Constraint(validatedBy = [])
    annotation class Contract(
        val message: String = "contract",
        val empty: String = "",
        val numbers: IntArray = [1, 2],
        val groups: Array<KClass<*>> = [],
        val payload: Array<KClass<out Payload>> = []
    )

    @Introspected(accessKind = [Introspected.AccessKind.FIELD], visibility = [Introspected.Visibility.ANY])
    class Bean(@field:Contract @field:ReflectiveAccess val value: String?)

    @Introspected(accessKind = [Introspected.AccessKind.FIELD], visibility = [Introspected.Visibility.ANY])
    class PrivateBean(@field:NotNull @field:ReflectiveAccess val value: String?,
                      @field:ReflectiveAccess val items: List<@NotNull String?>)
}
