package io.micronaut.docs.validation.custom

// tag::imports[]
import jakarta.validation.Constraint
import kotlin.annotation.AnnotationRetention.RUNTIME
// end::imports[]

// tag::class[]
@Retention(RUNTIME)
@Constraint(validatedBy = []) // <1>
annotation class DurationPattern(
    val message: String = "invalid duration ({validatedValue})" // <2>
)
// end::class[]
