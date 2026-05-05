plugins {
    id("io.micronaut.build.internal.validation-test-suite")
    id("org.jetbrains.kotlin.jvm")
    id("io.micronaut.build.internal.kotlin-base")
    id("io.micronaut.build.internal.kotlin-ksp")
}

dependencies {
    kspTest(projects.micronautValidationProcessor)
    kspTest(mn.micronaut.inject.kotlin)

    testImplementation(mn.micronaut.inject)
    testImplementation(libs.managed.validation)
    testImplementation(projects.micronautValidation)

    testImplementation(mn.micronaut.inject)

    testImplementation(libs.kotlin.test)
    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(libs.kotlin.kotest.junit5)
    testImplementation(mn.micronaut.http.client)
    testImplementation(mn.micronaut.http.server.netty)
    testImplementation(mn.micronaut.jackson.databind)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}
