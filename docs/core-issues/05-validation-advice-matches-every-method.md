# Validation advice is applied to every method of a type that has one constrained method

**Component:** `micronaut-core` — `core-processor` (`DefaultElementBeanDefinitionBuilderFactory`)
**Found on:** `claude/reflection-bridge`, measured from micronaut-validation `claude/core-reflection-bridge`
**Severity:** a live defect, independent of the TCK
**Unblocks:** 4 Jakarta Validation TCK methods

## Summary

When an AOP proxy is created for a bean, the methods to advise with the validation interceptor are selected
by (`core-processor/src/main/java/io/micronaut/inject/processing/definition/DefaultElementBeanDefinitionBuilderFactory.java:288`):

```java
if (target.hasAnnotation(ANN_REQUIRES_VALIDATION)) {
    if (target.hasStereotype(ConfigurationReader.class)) {
        …
    } else {
        for (MethodElement methodElement : target.getEnclosedElements(
                ElementQuery.ALL_METHODS.annotated(am -> am.hasAnnotation(ANN_REQUIRES_VALIDATION)))) {
            methodElement.annotate(ANN_VALIDATED);
        }
    }
}
```

The predicate receives the `MethodElement`, and a `MethodElement`'s `getAnnotationMetadata()` **combines the
class and the method** (`core-processor/src/main/java/io/micronaut/inject/ast/MethodElement.java:54`).
`ValidationVisitor` puts `@RequiresValidation` on the class as soon as *any* member of it needs validation.
So `am.hasAnnotation(ANN_REQUIRES_VALIDATION)` is true for **every** method of the type, and every method is
annotated `@Validated`.

## Why it matters

Two separate problems.

**A live defect.** A bean with one constrained method has all of its methods routed through
`ValidatingInterceptor`. The interceptor finds nothing to validate and returns, so nothing is *incorrect* —
but every call on that bean pays for an interception it does not need.

```java
@Singleton
class OrderService {
    void place(@NotNull String name) {}   // must be advised
    String describe() { return "x"; }     // gets advised too
    int count() { return 0; }             // and this
}
```

**A behaviour the specification requires is inexpressible.** Jakarta Validation's
`@ValidateOnExecution(ExecutableType.NONE)` turns *interception* off while the method must still be
*described* — `BeanDescriptor.getConstraintsForMethod` has to return a descriptor for it. Expressing that
means marking the method `@Executable` (so it becomes a bean method of the introspection) while keeping
`@RequiresValidation` off it. With the class-level annotation leaking through the query, that is impossible:
the method is re-advised regardless.

## Evidence

Implemented and measured in the micronaut-validation TCK harness: removing `@RequiresValidation` from the
methods whose execution must not be validated, and marking them `@Executable` instead of `@Vetoed`, makes
**twelve `integration.cdi.executable` tests fail**, all with a `ConstraintViolationException` from a call
that should not have been intercepted:

```
integration.cdi.executable.ExecutableValidationTest#testGettersAreNotValidatedByDefault
  jakarta.validation.ConstraintViolationException: getEvent.<return value>: must not be null
integration.cdi.executable.types.ExecutableTypesTest#testValidationOfConstrainedMethodWithExecutableTypeNONE
  jakarta.validation.ConstraintViolationException: createEvent.title: must not be null
… 10 more
```

The correct form is a few lines away in the same code base —
`DeclaredBeanElementCreator.makeInterceptedForValidationIfNeeded`
(`core-processor/src/main/java/io/micronaut/inject/processing/DeclaredBeanElementCreator.java:263`):

```java
// The method with constrains should be intercepted with the validation interceptor
if (element.hasDeclaredAnnotation(ANN_REQUIRES_VALIDATION)) {
    element.annotate(ANN_VALIDATED);
}
```

`hasDeclaredAnnotation`, not `hasAnnotation`.

## Proposed change

Narrow the predicate to the method's own metadata:

```java
ElementQuery.ALL_METHODS.annotated(am -> am.hasDeclaredAnnotation(ANN_REQUIRES_VALIDATION))
```

or, if the intent is to include what an overridden method declares but never the class,
`methodElement.getMethodAnnotationMetadata().hasAnnotation(ANN_REQUIRES_VALIDATION)` via an element
predicate.

## Tests

`inject-java/src/test/groovy/io/micronaut/inject/…`, using `buildBeanDefinition`:

```groovy
void "only the constrained method is advised for validation"() {
    given:
    def definition = buildBeanDefinition('test.OrderService', '''
package test;

import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotNull;

@Singleton
class OrderService {
    public void place(@NotNull String name) {}
    public String describe() { return "x"; }
}
''')

    when:
    def advised = definition.executableMethods.findAll {
        it.hasAnnotation("io.micronaut.validation.Validated")
    }*.methodName

    then:
    advised == ["place"]
}

void "a constrained return value advises only its own method"() {
    given:
    def definition = buildBeanDefinition('test.OrderService', '''
package test;

import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotNull;

@Singleton
class OrderService {
    @NotNull public String describe() { return null; }
    public int count() { return 0; }
}
''')

    expect:
    definition.executableMethods.findAll {
        it.hasAnnotation("io.micronaut.validation.Validated")
    }*.methodName == ["describe"]
}

void "a bean whose methods are all unconstrained is not advised"() {
    given:
    def definition = buildBeanDefinition('test.OrderService', '''
package test;

import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotNull;

@Singleton
class OrderService {
    @NotNull private String name;
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int count() { return 0; }
}
''')

    expect: "the constrained field does not advise unrelated methods"
    definition.executableMethods.findAll {
        it.hasAnnotation("io.micronaut.validation.Validated")
    }*.methodName == []
}
```

A behavioural check that the advice still fires belongs alongside them — build a context, call the
constrained method with `null`, expect `ConstraintViolationException`; call the unconstrained one and expect
it to return normally.

## What it unblocks

`metadata.ExecutableDescriptorIgnoresValidatedExecutableAnnotationSettingsTest` (2),
`metadata.BeanDescriptorTest#testGetConstrainedMethodsTypeGETTER` and
`#testGetConstrainedMethodsTypesGETTERAndNON_GETTER` — and it removes the unnecessary interception from every
Micronaut bean that has one constrained member.
