# Python Docs Disabled Test Inventory

This file tracks Python docs examples that are present but disabled because the direct port currently fails
with the Python compiler shipped with Micronaut core. Use it as the bug-fixing task list.

## Reconciliation

- Last generated command: `rg -n "@Disabled\(" test-suite-python/src/test/python/micronaut/docs`.
- Last full-suite command: `./gradlew :test-suite-python:test -Ppython-ci`.

## Migration Rules

- Do not define local copies of Micronaut annotation helpers or custom annotation shims in docs snippets.
  Standard Micronaut annotations are generated from imports.
- Do not add Java-style getters or setters to Python docs models. Prefer `@dataclass` models with idiomatic
  Python attributes.
- Beans whose methods are validated must be annotated with `@Validated` (`micronaut.validation`): the Python compiler
  compiler only bridges the methods of a class through the AOP proxy when the class carries an `@Around`
  stereotype, so the `@Validated` annotation that `micronaut-validation-processor` adds implicitly for Java is
  not enough.
- Prefer `@MicronautTest` with injected beans over `ApplicationContext.run()`.

## Active `@Disabled` Tests

| Test | Reason |
| --- | --- |
| `micronaut.docs.validation.iterable.BookInfoSpec` | Constraints on generic type arguments (`list[Annotated[str, NotBlank]]`, `dict[Annotated[str, NotBlank], Annotated[int, Min(1)]]`) are recorded in the argument metadata, but the `io.micronaut.validation.annotation.ValidatedElement` marker that `ValidationVisitor` adds to the type argument is lost by the Python compiler, so `DefaultValidator` skips the container elements. Parameter-level constraints on the same methods work. |

## Commented Unsupported Snippet Ports

None.

## Intentionally Unsupported Snippet Targets

| Target | Reason |
| --- | --- |
| `io.micronaut.docs.validation.custom.DurationPatternValidatorSpec#testServiceLoader` and the `META-INF/services/io.micronaut.validation.validator.constraints.ConstraintValidator` registration | A compile-time `ConstraintValidator` must be on the annotation processor classpath as a pre-built class; a Python class compiled in the same module cannot be service-loaded by the compiler. The `DurationPatternValidator` class itself is ported. |
| `io.micronaut.docs.validation.custom.HolidayService#startHoliday(fromDuration, toDuration, person)` overload and `DurationPatternValidatorSpec#testCustomAndDefaultValidator` | Python has no method overloading; as in the Kotlin and Groovy suites only the documented `start_holiday(person, duration)` method is ported. |
