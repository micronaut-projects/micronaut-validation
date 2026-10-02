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

import io.micronaut.core.annotation.Introspected
import io.micronaut.core.annotation.ReflectiveAccess
import io.micronaut.validation.validator.Validator
import jakarta.validation.constraints.NotNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GeneratedKotlinMetadataTest {
    @Test
    fun privateFieldsRequireTheCompanionEvenWithNativeRegistration() {
        val failure = assertThrows(jakarta.validation.ValidationException::class.java) {
            Validator.getInstance().validate(PrivateBean(null, listOf(null)))
        }
        assertTrue(failure.cause!!.message!!.contains("micronaut-validation-reflection"))
    }

    @Test
    fun generatedPropertiesAndNestedArgumentsWorkThroughKsp() {
        val violations = Validator.getInstance().validate(PropertyBean(null, listOf(null)))
        assertEquals(2, violations.size)
    }

    @Introspected
    class PropertyBean(@get:NotNull val value: String?, val items: List<@NotNull String?>)

    @Introspected(accessKind = [Introspected.AccessKind.FIELD], visibility = [Introspected.Visibility.ANY])
    class PrivateBean(@field:NotNull @field:ReflectiveAccess val value: String?,
                      @field:ReflectiveAccess val items: List<@NotNull String?>)
}
