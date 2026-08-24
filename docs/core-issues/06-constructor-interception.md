# Constructors are not intercepted, so a constrained constructor is never validated on invocation

**Component:** `micronaut-core` — AOP (`core-processor` proxy generation, `aop`)
**Found on:** `claude/reflection-bridge`, measured from micronaut-validation `claude/core-reflection-bridge`
**Unblocks:** 2 Jakarta Validation TCK methods
**Recommendation:** large; reasonable to close as a documented boundary instead

## Summary

Micronaut AOP intercepts methods. A constructor cannot be advised, so constraints declared on a
constructor's parameters or on its return value are never enforced when the container creates the bean.

## Why it matters

Jakarta Validation section 5.1.2 requires an integration to validate constrained constructors the container
invokes, the same way it validates constrained methods:

```java
@Singleton
class Event {
    Event(@NotNull String title) {}          // parameters must be validated on construction
}

@Singleton
class Order {
    @ValidOrder Order(String name) {}        // the return value — the new instance — must be validated
}
```

`jakarta.validation.executable.ExecutableType.CONSTRUCTORS` exists precisely to select this.

Note that the *metadata* half is separate and tracked as issue 01: describing a constructor
(`BeanDescriptor.getConstraintsForConstructor`) and validating one explicitly through
`Validator.forExecutables().validateConstructorParameters(...)` both work once the introspection lists every
constructor. This issue is only about the container enforcing them on invocation.

## Current behaviour

The two TCK tests below invoke a constrained constructor through the container and assert a
`ConstraintViolationException`. Nothing is thrown — the call goes straight to the constructor:

```
integration.cdi.executable.ExecutableValidationTest#testReturnValueValidationOfConstrainedConstructor
  AssertionError: Constructor invocation should have caused a ConstraintViolationException
integration.cdi.executable.types.ExecutableTypesTest#testValidationOfConstrainedConstructorReturnValueWithExecutableTypeCONSTRUCTORS
  AssertionError: Constructor invocation should have caused a ConstraintViolationException
```

## What it would take

A compile-time proxy cannot wrap a constructor the way it wraps a method: the proxy *is* constructed. Two
shapes are plausible, and both are substantial:

- **Validate at the bean-creation boundary.** Have the generated bean definition validate the resolved
  constructor arguments before `instantiate`, and the resulting instance after, when the constructor carries
  constraints. This covers beans the container creates — which is what the specification asks of an
  integration — and leaves `new Event(...)` in user code alone.
- **Interceptor-aware factory methods.** Route construction of an advised type through a generated factory
  that can run an interceptor chain around the instantiation.

The first is narrower and matches the specification's scope. It needs `RequiresValidation` on the
constructor to reach the bean definition, a hook in `BeanDefinitionWriter` around the constructor call, and
a decision about what happens for a `@Factory` method or a record.

## Tests

```groovy
void "a constrained constructor parameter is validated when the container creates the bean"() {
    given:
    def context = buildContext('test.Event', '''
package test;

import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotNull;

@Singleton
class Event {
    Event(@NotNull String title) {}
}
''')

    when:
    context.getBean(context.classLoader.loadClass('test.Event'))

    then:
    thrown(jakarta.validation.ConstraintViolationException)

    cleanup:
    context.close()
}

void "an unconstrained constructor is not intercepted"() {
    given:
    def context = buildContext('test.Event', '''
package test;

import jakarta.inject.Singleton;

@Singleton
class Event {
    Event(String title) {}
}
''')

    expect:
    context.getBean(context.classLoader.loadClass('test.Event')) != null

    cleanup:
    context.close()
}

void "a cascaded constructor parameter is validated recursively"() {
    given:
    def context = buildContext('test.Order', '''
package test;

import jakarta.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import io.micronaut.core.annotation.Introspected;

@Introspected
class Line {
    @NotNull String name;
}

@Singleton
class Order {
    Order(@Valid Line line) {}
}
''')
    // with a Line bean whose name is null

    when:
    context.getBean(context.classLoader.loadClass('test.Order'))

    then:
    thrown(jakarta.validation.ConstraintViolationException)

    cleanup:
    context.close()
}
```

## If it is not implemented

State the boundary in the documentation and in the TCK suite comment: Micronaut validates constrained
*methods* on invocation; a constrained constructor is described and can be validated explicitly through
`Validator.forExecutables()`, but the container does not enforce it on construction.
