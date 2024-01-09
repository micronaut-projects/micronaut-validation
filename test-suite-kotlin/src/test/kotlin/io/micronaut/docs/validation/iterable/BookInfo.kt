package io.micronaut.docs.validation.iterable

// tag::object[]
data class BookInfo(val authors: List<String>, // <1>
                    val sectionStartPages: Map<String, Int> // <2>
)
// end:object[]
