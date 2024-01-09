package io.micronaut.docs.validation.custom

import java.time.Duration
import jakarta.inject.Singleton
import jakarta.validation.constraints.NotBlank

// tag::class[]
@Singleton
open class HolidayService {

    open fun startHoliday(@NotBlank person: String,
                          @DurationPattern duration: String): String {
        val d = Duration.parse(duration)
        return "Person $person is off on holiday for ${d.toMinutes()} minutes"
    }
}
// end::class[]
