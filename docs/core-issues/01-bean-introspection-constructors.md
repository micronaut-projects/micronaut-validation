# A bean introspection describes only the constructor it instantiates beans with

**Component:** `micronaut-core` — `core` (API), `core-processor` (`BeanIntrospectionWriter`)
**Found on:** `claude/reflection-bridge`, measured from micronaut-validation `claude/core-reflection-bridge`
**Unblocks:** 41 Jakarta Validation TCK methods

## Summary

`BeanIntrospection` exposes one constructor. Everything a caller can learn about the constructors of an
introspected type comes from `getConstructor()`
(`core/src/main/java/io/micronaut/core/beans/BeanIntrospection.java:369`), which is synthesised from
`getConstructorArguments()` and describes the single constructor the introspection instantiates beans with.

A specification that *describes* a type rather than instantiating it needs all of them. Jakarta Validation
names a constructor by its parameter types and describes each one: its parameters, its cross-parameter
constraints, its return value, whether either is cascaded, and the group conversions on both.

## Current behaviour

```java
BeanIntrospection<Order> introspection = BeanIntrospector.SHARED.getIntrospection(Order.class);
introspection.getConstructor();   // the bean-instantiating one, always exactly one
// there is no getConstructors()
```

Nothing in the generated introspection carries the annotations of a constructor other than the one chosen to
build beans, and nothing carries the annotations of *its* parameters either — only
`getConstructorArguments()`, for that one constructor.

## Why it matters

`jakarta.validation.metadata.BeanDescriptor` has `getConstrainedConstructors()` and
`getConstraintsForConstructor(Class<?>...)`. With one constructor available, the validator returns `null`
for any constructor the introspection did not pick, and the TCK then fails with a
`NullPointerException` on `ConstructorDescriptor`.

The contract needed is already written down in micronaut-core, on the reflective side of the same bridge —
`inject/src/main/java/io/micronaut/inject/reflection/ReflectiveIntrospection.java`:

```java
/**
 * @return Every constructor of the type, {@link #getConstructor()} first
 */
List<BeanConstructor<T>> getConstructors();
```

That is what makes the full TCK profile pass today; only the generated introspections lack it.

## Reproduction

```java
@Introspected
class Order {
    Order() {}
    Order(@NotNull String name) {}
    @ValidOrder Order(String name, int quantity) {}
}
```

`BeanIntrospector.SHARED.getIntrospection(Order.class)` describes exactly one of the three, and the
annotations of the other two — including `@NotNull` on the parameter and `@ValidOrder` on the constructor —
are absent from the introspection.

## Proposed change

1. **API.** Promote `getConstructors()` from `ReflectiveIntrospection` to `BeanIntrospection`, with the same
   contract: every declared constructor, `getConstructor()` first. A `default` implementation returning
   `List.of(getConstructor())` keeps every existing introspection valid.
2. **Writer.** `BeanIntrospectionWriter` emits one `BeanConstructor` per declared constructor, each with its
   own `AnnotationMetadata` and its own `Argument[]` — parameter annotations and type-use annotations
   included. Today only the bean-instantiating constructor's arguments are written.
3. **Gate.** Writing every constructor of every introspected type is not free. Put it behind an
   `@Introspected` member — for example `constructors = true` — so a module that needs it (the validation
   processor, via `IntrospectedValidationIndexesVisitor`) can ask for it and nobody else pays.

`BeanConstructor` already has everything needed (`getDeclaringBeanType()`, `getAnnotationMetadata()`,
`getArguments()`, `instantiate(Object...)`), so no new type is required.

## Tests

`inject-java-test/src/test/groovy/io/micronaut/inject/visitor/beans/BeanIntrospectionSpec.groovy`, in the
style already used there:

```groovy
void "every declared constructor is described"() {
    given:
    def introspection = buildBeanIntrospection('test.Order', '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotNull;

@Introspected(constructors = true)
class Order {
    Order() {}
    Order(@NotNull String name) {}
    Order(String name, int quantity) {}
}
''')

    when:
    def constructors = introspection.getConstructors()

    then:
    constructors.size() == 3
    // getConstructor() first, per the contract
    constructors[0].arguments.length == introspection.constructor.arguments.length
    constructors*.arguments*.length.toSorted() == [0, 1, 2]
}

void "a constructor carries the annotations of its parameters"() {
    given:
    def introspection = buildBeanIntrospection('test.Order', '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Introspected(constructors = true)
class Order {
    Order() {}
    Order(@NotNull String name, List<@Valid Line> lines) {}
}

class Line {}
''')

    when:
    def constructor = introspection.getConstructors().find { it.arguments.length == 2 }

    then:
    constructor.arguments[0].annotationMetadata.hasAnnotation(NotNull)
    constructor.arguments[1].typeParameters[0].annotationMetadata.hasAnnotation('jakarta.validation.Valid')
}

void "a constructor carries its own annotations"() {
    given:
    def introspection = buildBeanIntrospection('test.Order', '''
package test;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.context.annotation.Parameter;

@Introspected(constructors = true)
class Order {
    Order() {}
    @Deprecated Order(String name) {}
}
''')

    expect:
    introspection.getConstructors().find { it.arguments.length == 1 }.annotationMetadata.hasAnnotation(Deprecated)
}

void "an introspection that did not ask for constructors keeps describing one"() {
    given:
    def introspection = buildBeanIntrospection('test.Order', '''
package test;

import io.micronaut.core.annotation.Introspected;

@Introspected
class Order {
    Order() {}
    Order(String name) {}
}
''')

    expect:
    introspection.getConstructors().size() == 1
    introspection.getConstructors()[0].arguments.length == introspection.constructor.arguments.length
}

void "instantiating through a described constructor works"() {
    given:
    def introspection = buildBeanIntrospection('test.Order', '''
package test;

import io.micronaut.core.annotation.Introspected;

@Introspected(constructors = true)
class Order {
    final String name;
    Order() { this.name = "none"; }
    Order(String name) { this.name = name; }
    public String getName() { return name; }
}
''')

    when:
    def order = introspection.getConstructors().find { it.arguments.length == 1 }.instantiate("abc")

    then:
    introspection.getRequiredProperty("name", String).get(order) == "abc"
}
```

Worth covering the same in `inject-groovy` and `inject-kotlin`, where records, secondary constructors and
`@JvmOverloads` all produce more than one constructor.

## What it unblocks

`metadata.ExecutableDescriptorTest` (17), `metadata.CrossParameterDescriptorTest` (6),
`metadata.BeanDescriptorTest` (5), `metadata.ReturnValueDescriptorTest` (4),
`metadata.ParameterDescriptorTest` (3), `methodvalidation.ValidateConstructorReturnValueTest` (2),
`MethodValidationRequirementTest#testReturnValueConstraintsAreDeclaredByAnnotatingConstructors`,
`ValidatorResolutionTest#testTargetedTypeIsConstructor`,
`InvalidDeclarationOfGenericAndCrossParameterConstraintTest#testConstraintTargetParametersOnConstructorWithoutParametersCausesException`,
`InvalidGroupDefinitionsTest#testGroupConversionWithoutValidAnnotationOnConstructorReturnValue`.

On the micronaut-validation side it also deletes two `ReflectiveIntrospection` special cases —
`IntrospectedBeanDescriptor.constructors()` and `ReflectiveExecutables.beanConstructor`.

Two further TCK tests invoke a constrained constructor and expect the container to have validated it; those
need constructor *interception*, which is issue 06.
