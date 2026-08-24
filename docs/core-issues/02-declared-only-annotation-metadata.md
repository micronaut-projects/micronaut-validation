# No way to read the annotations an element declares, apart from the ones it inherits

**Component:** `micronaut-core` — `inject` (`AnnotationMetadata`), `core-processor` (`MethodElement`)
**Found on:** `claude/reflection-bridge`, measured from micronaut-validation `claude/core-reflection-bridge`
**Unblocks:** 19 Jakarta Validation TCK methods

## Summary

Given a method that overrides another, there is no reliable way to ask for the annotations *this* declaration
carries, separately from the ones it inherits, while keeping repeatable annotations readable.

Three things stand between a caller and that answer:

- `MethodElement.getAnnotationMetadata()` combines the class and the method
  (`core-processor/src/main/java/io/micronaut/inject/ast/MethodElement.java:54`).
- `MethodElement.getMethodAnnotationMetadata()` drops the class but is documented as "annotations defined on
  a method **or inherited from the super methods**" (same file, line 52).
- `AnnotationMetadata.getDeclaredMetadata()` builds a `DefaultAnnotationMetadata` from
  `declaredAnnotations`/`declaredStereotypes` with `allAnnotations` and `allStereotypes` set to `null`
  (`inject/src/main/java/io/micronaut/inject/annotation/DefaultAnnotationMetadata.java:168`), so lookups that
  go through the repeatable-container mapping stop finding values.

## Why it matters

Jakarta Validation distinguishes what a type declares from what it inherits in four places:

- `ElementDescriptor.ConstraintFinder.lookingAt(Scope.LOCAL_ELEMENT)`
- `ElementDescriptor.ConstraintFinder.declaredOn(ElementType...)`
- the *implicit group* of a constraint, which is the type declaring it
- the declarations the specification forbids across a hierarchy: a parameter constrained in two parallel
  interfaces, a return value marked cascaded in both an interface and its implementation, a group conversion
  added in an overriding method

micronaut-validation models this with `ExecutableHierarchy.Declaration`, which already carries an `exact`
flag meaning "the annotations are the ones of this declaration only", and the rules branch on it.

## Evidence

The workaround was implemented and measured. Marking a declaration read from a generated introspection as
`exact` — one line in `ExecutableHierarchy.declaredBy` — makes **two more TCK tests pass and seven regress**:

```
methodvalidation.MethodValidationTest#methodParameterValidationIncludesConstraintsFromImplementedInterface
  jakarta.validation.ConstraintDeclarationException: Parameter constraints cannot be added in overriding or implementing methods
methodvalidation.MethodValidationTest#methodParameterValidationIncludesConstraintsFromSuperClass
  jakarta.validation.ConstraintDeclarationException: Parameter constraints cannot be added in overriding or implementing methods
integration.cdi.executable.ExecutableValidationTest#testExecutableValidationUsesDefaultSettingIfValidatedMethodOverridesASuperTypeMethod
… 4 more
```

The exception is wrong every time: the overriding method declares nothing, but its metadata already contains
what the interface declared, so "declared" reads as more than the type declares.

The same limitation is written into micronaut-validation as a comment, in
`ExecutableHierarchy.declaredOf`:

```java
/**
 * The annotations declared on the executable, without the ones of its class: the executable methods of
 * beans carry both. A metadata that is not a hierarchy is returned as is, {@code getDeclaredMetadata()}
 * would drop the repeated annotations.
 */
```

— which is why the narrowing is applied to an `AnnotationMetadataHierarchy` and skipped for anything else.

## Reproduction

```java
interface Contract {
    void place(@NotNull String name);
}

class Impl implements Contract {
    @Override
    public void place(String name) {}       // declares nothing
}
```

For `Impl.place`, every metadata reachable today reports `@NotNull` on the parameter. There is no view that
reports "this declaration constrains nothing".

## Proposed change

Either of:

**(a) A declared-only view that survives repeatable containers.** Make `getDeclaredMetadata()` (or a new
`getDeclaredOnly()`) keep enough of the container mapping that `getAnnotationValuesByType` and
`getAnnotationValuesByName` still resolve `Foo$List`-style containers among the declared annotations. Add the
same on `MethodElement` — a `getDeclaredMethodAnnotationMetadata()` that excludes the super methods, next to
the existing `getMethodAnnotationMetadata()` that includes them.

**(b) Per-declaration metadata on the generated members.** Retain, on the generated `BeanMethod`,
`BeanProperty` and `Argument`, the metadata of each level of the hierarchy with the type declaring it, the
way `ReflectiveIntrospection.PropertyMember` already does on the reflective side. This subsumes issue 03.

(a) is smaller and fixes the general problem; (b) is the faithful model.

## Tests

For the metadata narrowing, in the `inject` test suite:

```groovy
void "the declared metadata keeps repeatable annotations"() {
    given:
    def metadata = buildTypeAnnotationMetadata('''
package test;

import jakarta.validation.constraints.Size;

@Size(min = 1)
@Size(min = 2, groups = Other.class)
class Test {}

interface Other {}
''')

    when:
    def declared = metadata.getDeclaredMetadata()

    then:
    declared.getAnnotationValuesByType(Size).size() == 2
}
```

For the element side, in `inject-java/src/test/groovy/io/micronaut/annotation/processing/visitor/`:

```groovy
void "the declared method metadata excludes what the overridden method declares"() {
    given:
    def element = buildClassElement('''
package test;

import jakarta.validation.constraints.NotNull;

interface Contract {
    @Deprecated
    void place(@NotNull String name);
}

class Test implements Contract {
    @Override
    public void place(String name) {}
}
''')
    def method = element.findMethod("place").get()

    expect:
    // what it inherits is still visible where it always was
    method.getMethodAnnotationMetadata().hasAnnotation(Deprecated)
    method.getParameters()[0].hasAnnotation(NotNull)
    // and what it declares is now askable
    !method.getDeclaredMethodAnnotationMetadata().hasAnnotation(Deprecated)
}

void "a method that declares the annotation itself still reports it as declared"() {
    given:
    def element = buildClassElement('''
package test;

interface Contract {
    @Deprecated
    void place(String name);
}

class Test implements Contract {
    @Override
    @Deprecated
    public void place(String name) {}
}
''')

    expect:
    element.findMethod("place").get().getDeclaredMethodAnnotationMetadata().hasAnnotation(Deprecated)
}
```

An end-to-end check belongs in micronaut-validation: with the narrowing in place,
`ExecutableHierarchy.declaredBy` can mark generated declarations `exact` and
`:micronaut-tests:micronaut-jakarta-validation-tck:jakartaTckIntrospection` must stay green while the
exclusion list shrinks.

## What it unblocks

`InvalidGroupDefinitionsTest` (3), `InvalidGroupDefinitionsOnContainerElementsTest` (2),
`InvalidMethodConstraintDeclarationTest` (2), `ValidMethodConstraintDeclarationTest` (1),
`MethodValidationTest` (2), `CrossParameterDescriptorTest` (2 `lookingAt` cases),
`InvalidDeclarationOfGenericAndCrossParameterConstraintTest` (2 interface cases),
`GroupTest#testImplicitGrouping`, `ConstraintDescriptorTest#testGetGroupsWithImplicitGroup`,
`GroupSequenceIsolationTest#testCorrectDefaultSequenceInheritance3`, and
`ConvertGroupDefaultFromTest#testConvertGroupDefaultFromForMethodReturnValue`.
