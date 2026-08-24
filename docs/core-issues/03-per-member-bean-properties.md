# A generated bean property merges a field and its getter into one member

**Component:** `micronaut-core` — `core` (`BeanProperty`), `core-processor` (`BeanIntrospectionWriter`)
**Found on:** `claude/reflection-bridge`, measured from micronaut-validation `claude/core-reflection-bridge`
**Unblocks:** 7 Jakarta Validation TCK methods

## Summary

With `@Introspected(accessKind = {FIELD, METHOD})`, a field and its getter become one `BeanProperty` with one
merged `AnnotationMetadata`, read through the getter. There is no way to ask which member declared which
annotation, which kind of member it is, or to read the value the *field* holds.

`BeanProperty.getDeclaringType()` returns the bean type, not the type declaring the member
(`core/src/main/java/io/micronaut/core/beans/BeanProperty.java:295`).

## Why it matters

Jakarta Validation treats a field and a getter as two constrained elements:

- a constraint on the field is validated against the value **the field holds**; one on the getter against
  **what the getter returns**. They can differ.
- the `TraversableResolver` is told `ElementType.FIELD` or `ElementType.METHOD` for each
- `ElementDescriptor.ConstraintFinder.declaredOn(ElementType...)` filters on it
- constraints declared on both add up rather than one shadowing the other

## Current behaviour

```java
@Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD})
class Person {
    @NotNull private String name = "Billy";
    public String getName() { return "Bob"; }
}
```

The introspection reports one property `name`, one metadata carrying `@NotNull`, and `get(bean)` returns
`"Bob"`. The specification asks for `"Billy"` to be passed to the validator of the field-level constraint —
this is `validation.ValueAccessStrategyTest#testValueFromFieldIsPassedToValidatorOfFieldLevelConstraint`.

## The contract already exists on the reflective side

`inject/src/main/java/io/micronaut/inject/reflection/ReflectiveIntrospection.java`:

```java
List<PropertyMember> getPropertyMembers(String propertyName);

record PropertyMember(ElementType elementType,
                      Class<?> declaringType,
                      AnnotationMetadata annotationMetadata,
                      Argument<?> argument,
                      AnnotatedElement member) {
    boolean isReadable();
    Object read(Object bean);
}
```

micronaut-validation already consumes exactly this shape — `DefaultValidator.visitPropertyMember` and
`IntrospectedBeanDescriptor.IntrospectedPropertyDescriptor.members()` — which is why the full TCK profile
passes. Only the generated introspections cannot answer.

## Proposed change

Give a generated `BeanProperty` the same per-member view. Concretely, per member:

- `ElementType.FIELD` or `ElementType.METHOD`
- the class declaring it
- its own `AnnotationMetadata`, type-use annotations of its type included
- its own `Argument<?>` — an interface getter can declare `Iterable<@NotNull String>` where the
  implementation returns a `Set`
- a **generated** accessor for reading it, so reading the field does not fall back to reflection

The first three overlap with issue 02 and could ride the same per-declaration metadata. The accessor does
not: reading a field rather than the getter needs the writer to emit a field read.

As with issue 01, this should be opt-in through an `@Introspected` member so introspections that do not need
it do not grow.

## Tests

`inject-java-test/src/test/groovy/io/micronaut/inject/visitor/beans/BeanIntrospectionSpec.groovy`:

```groovy
void "a property lists the field and the getter as separate members"() {
    given:
    def introspection = buildBeanIntrospection('test.Person', '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD}, members = true)
class Person {
    @NotNull private String name;
    @Size(min = 5) public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
''')

    when:
    def members = introspection.getPropertyMembers("name")

    then:
    members.findAll { it.readable() }.size() == 2
    members.find { it.elementType() == ElementType.FIELD }.annotationMetadata().hasAnnotation(NotNull)
    members.find { it.elementType() == ElementType.METHOD && it.readable() }.annotationMetadata().hasAnnotation(Size)
}

void "the field member reads the field and the getter member reads the getter"() {
    given:
    def introspection = buildBeanIntrospection('test.Person', '''
package test;

import io.micronaut.core.annotation.Introspected;

@Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD}, members = true)
class Person {
    private String name = "Billy";
    public String getName() { return "Bob"; }
}
''')
    def person = introspection.instantiate()

    when:
    def members = introspection.getPropertyMembers("name").findAll { it.readable() }

    then:
    members.find { it.elementType() == ElementType.FIELD }.read(person) == "Billy"
    members.find { it.elementType() == ElementType.METHOD }.read(person) == "Bob"
}

void "a member names the type declaring it"() {
    given:
    def introspection = buildBeanIntrospection('test.Impl', '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Size;
import java.util.Set;

interface Contract {
    @Size(min = 5) String getName();
}

@Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD}, members = true)
class Impl implements Contract {
    @Override public String getName() { return null; }
}
''')

    expect:
    introspection.getPropertyMembers("name")*.declaringType()*.simpleName.contains("Contract")
}

void "a member keeps the container the declaration used"() {
    given:
    def introspection = buildBeanIntrospection('test.Impl', '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

interface Contract {
    Iterable<@NotNull String> getRoles();
}

@Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD}, members = true)
class Impl implements Contract {
    @Override public Set<String> getRoles() { return null; }
}
''')

    when:
    def members = introspection.getPropertyMembers("roles")

    then:
    members*.argument()*.type.contains(Iterable)
    members.find { it.argument().type == Iterable }.argument().typeParameters[0].annotationMetadata.hasAnnotation(NotNull)
}

void "an introspection that did not ask for members reports none"() {
    given:
    def introspection = buildBeanIntrospection('test.Person', '''
package test;

import io.micronaut.core.annotation.Introspected;

@Introspected
class Person {
    private String name;
    public String getName() { return name; }
}
''')

    expect:
    introspection.getPropertyMembers("name").isEmpty()
}
```

## What it unblocks

`traversableresolver.TraversableResolverTest` (3),
`constraints.application.ValidationRequirementTest#testConstraintAppliedOnFieldAndProperty` and
`#testFieldAccess`, `metadata.ElementDescriptorTest#testDeclaredOn`,
`validation.ValueAccessStrategyTest#testValueFromFieldIsPassedToValidatorOfFieldLevelConstraint`, and
`constraints.inheritance.ConstraintInheritanceTest#testValidationConsidersConstraintsFromSuperTypes`, which
needs the validation path to visit a property once per declaring level with a per-level value read.
