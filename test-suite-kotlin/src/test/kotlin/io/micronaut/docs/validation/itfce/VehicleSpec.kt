package io.micronaut.docs.validation.itfce

import io.kotest.assertions.throwables.shouldThrow
import io.micronaut.context.ApplicationContext
import io.micronaut.context.exceptions.BeanInstantiationException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VehicleSpec {

    @Test
    fun testValid() {
        // tag::start[]
        val map = mapOf(
                "my.engine.cylinders" to "8",
                "my.engine.crank-shaft.rod-length" to "7.0"
        )
        val applicationContext = ApplicationContext.run(map)

        val vehicle = applicationContext.getBean(Vehicle::class.java)
        // end::start[]

        assertEquals("Ford Engine Starting V8 [rodLength=7.0]", vehicle.start())

        applicationContext.close()
    }

    @Test
    fun testInvalid() {
        // tag::start[]
        val map = mapOf(
                "my.engine.cylinders" to "-10",
                "my.engine.crank-shaft.rod-length" to "7.0"
        )
        val applicationContext = ApplicationContext.run(map)
        val exception = shouldThrow<BeanInstantiationException> {
            applicationContext.getBean(Vehicle::class.java)
        }
        assertTrue(exception.message!!.contains("EngineConfig.getCylinders - must be greater than or equal to 1"))

        applicationContext.close()
    }

}
