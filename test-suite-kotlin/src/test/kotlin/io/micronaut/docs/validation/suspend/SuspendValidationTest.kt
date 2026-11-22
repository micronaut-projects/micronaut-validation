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
package io.micronaut.docs.validation.suspend

import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.validation.ConstraintViolationException
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

@Singleton
open class SuspendingService {

    @NotBlank
    open suspend fun suspendingName(name: String): String {
        delay(1)
        return name
    }

    @NotBlank
    open suspend fun immediateName(name: String): String = name

    @NotNull
    open suspend fun suspendingNullable(name: String?): String? {
        delay(1)
        return name
    }

    open suspend fun greet(@NotBlank name: String): String {
        delay(1)
        return "Hello $name"
    }
}

/**
 * The return value of a suspending method is the value the coroutine produces.
 */
@MicronautTest
class SuspendValidationTest {

    @Inject
    lateinit var service: SuspendingService

    @Test
    fun aValidValueOfASuspendedMethodIsReturned() {
        assertEquals("abc", runBlocking { service.suspendingName("abc") })
        assertEquals("abc", runBlocking { service.immediateName("abc") })
        assertEquals("Hello abc", runBlocking { service.greet("abc") })
    }

    @Test
    fun theValueASuspendedMethodProducesIsValidated() {
        val exception = assertThrows(ConstraintViolationException::class.java) {
            runBlocking { service.suspendingName("") }
        }
        assertEquals(1, exception.constraintViolations.size, exception.message)
    }

    @Test
    fun theValueAMethodNotSuspendingProducesIsValidated() {
        val exception = assertThrows(ConstraintViolationException::class.java) {
            runBlocking { service.immediateName("") }
        }
        assertEquals(1, exception.constraintViolations.size, exception.message)
    }

    @Test
    fun aNullValueOfASuspendedMethodIsValidated() {
        val exception = assertThrows(ConstraintViolationException::class.java) {
            runBlocking { service.suspendingNullable(null) }
        }
        assertEquals(1, exception.constraintViolations.size, exception.message)
    }

    @Test
    fun theParametersOfASuspendingMethodAreValidated() {
        assertThrows(ConstraintViolationException::class.java) {
            runBlocking { service.greet("") }
        }
    }
}
