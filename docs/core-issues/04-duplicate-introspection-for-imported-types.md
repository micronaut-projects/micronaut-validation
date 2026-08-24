# An imported type gets its introspection generated twice and the build fails

**Component:** `micronaut-core` — `core-processor` (`IntrospectedTypeElementVisitor`, `BeanIntrospectionWriter`)
**Found on:** `claude/reflection-bridge`, measured from micronaut-validation `claude/core-reflection-bridge`
**Unblocks:** 20 Jakarta Validation TCK methods

## Summary

Naming a type on `@Introspected(classNames = …)` or `@ClassImport(classNames = …)` generates its
introspection twice for some types, and the second `visitServiceDescriptor` call fails the compilation:

```
error: Failed to generate class:
  'test.$test_Outer$Nested$Introspection': Unable to generate Bean entry at path:
  META-INF/micronaut/io.micronaut.core.beans.BeanIntrospectionReference/test.$test_Outer$Nested$Introspection
```

The `Filer` refuses to recreate a resource it already created for this compilation.

## Why anyone hits this

The annotation processor visits the types a round hands it: the top-level ones from
`roundEnv.getRootElements()`, plus whatever `getElementsAnnotatedWith` returns for the annotations present
(`inject-java/src/main/java/io/micronaut/annotation/processing/TypeElementVisitorProcessor.java:255-263`).

A **nested type carrying no annotation of its own is therefore never visited**, and so never introspected —
even when the enclosing file is compiled. That is the normal case for a type whose constraints live in an
XML mapping, or one that is only ever cascaded into. The documented way to reach such a type from outside is
exactly `@Introspected(classNames = …)` or `@ClassImport(classNames = …)`, and that is where the duplicate
appears.

## Root cause

`IntrospectedTypeElementVisitor.isIntrospected`
(`core-processor/src/main/java/io/micronaut/inject/beans/visitor/IntrospectedTypeElementVisitor.java:101`):

```java
private boolean isIntrospected(VisitorContext context, ClassElement c) {
    return processed.contains(c.getName())
        || context.getClassElement(c.getPackageName() + ".$" + c.getSimpleName() + "$Introspection").isPresent();
}
```

The second clause looks for `<package>.$<Simple>$Introspection`. An introspection generated *on behalf of*
another element — the `classNames`/`@ClassImport` case — is written as
`$<originating class, dots to underscores>$<Simple>$Introspection`, so the lookup never matches it, and the
guard falls back to the in-memory `processed` set, which does not survive whatever re-visits the element.

## Evidence

Both mechanisms were implemented in the micronaut-validation TCK harness and measured.

- Both bring the missing types in: the suite goes from **1054 to 1067 executed tests**, and the
  `Bean introspection not found for the class: …` failures disappear.
- Both then fail the build on a handful of types, with the error above. With `@ClassImport` the run was
  `1067 tests completed, 21 failed, 113 skipped`, the 21 all downstream of 7 deployments that would not
  compile.
- Instrumenting the harness visitor with a per-class visit counter shows the imported element visited more
  than once, each time carrying `@ImportedClass`.
- Removing `AggregatingTypeElementVisitorProcessor` from the processor list changes nothing, so it is not
  the two-processor registration.

The affected types in that run were a mix — `private static class NestedCascadingListWithValidAllAlongTheWay`,
`public static class FishTank`, a nested `@interface` — so it is not visibility or element kind.

## Proposed change

Make the guard cover introspections generated on behalf of another element. Either:

- key the "already generated" check on the **target class**, not on a name derived from it — for example
  record the target class names for which a `BeanIntrospectionReference` descriptor has been written in this
  compilation and consult that set; or
- have `visitServiceDescriptor` be idempotent per path for a compilation, so writing the same descriptor
  twice is a no-op rather than an `IOException`.

The first is the narrower fix and keeps the failure available for genuine name collisions.

## Tests

`inject-java-test/src/test/groovy/io/micronaut/inject/visitor/beans/BeanIntrospectionSpec.groovy`:

```groovy
void "a nested type named on classNames is introspected once"() {
    given:
    def context = buildContext('test.Holder', '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotNull;

@Introspected(classNames = {"test.Outer$Nested"})
class Holder {}

class Outer {
    @Introspected
    static class Nested {
        @NotNull private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
''')

    expect: "it compiles, and exactly one reference is registered"
    context.getBeansOfType(BeanIntrospectionReference)
        .findAll { it.beanType.name == 'test.Outer$Nested' }
        .size() == 1

    cleanup:
    context.close()
}

void "a type named on ClassImport and annotated itself is introspected once"() {
    given:
    def context = buildContext('test.Holder', '''
package test;

import io.micronaut.context.annotation.ClassImport;
import io.micronaut.core.annotation.Introspected;

@ClassImport(classNames = {"test.Outer$Nested"})
class Holder {}

class Outer {
    @Introspected
    static class Nested {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
''')

    expect:
    context.getBeansOfType(BeanIntrospectionReference)
        .findAll { it.beanType.name == 'test.Outer$Nested' }
        .size() == 1

    cleanup:
    context.close()
}

void "a nested type carrying no annotation is introspected when named"() {
    given:
    def introspection = buildBeanIntrospection('test.Outer$Nested', '''
package test;

import io.micronaut.core.annotation.Introspected;

@Introspected(classNames = {"test.Outer$Nested"})
class Holder {}

class Outer {
    static class Nested {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
''')

    expect:
    introspection.propertyNames as Set == ["name"] as Set
}

void "naming the same type twice does not fail the build"() {
    given:
    def context = buildContext('test.Holder', '''
package test;

import io.micronaut.context.annotation.ClassImport;

@ClassImport(classNames = {"test.Outer$Nested"})
@ClassImport(classNames = {"test.Outer$Nested"})
class Holder {}

class Outer {
    static class Nested {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
''')

    expect:
    context.getBeansOfType(BeanIntrospectionReference)
        .findAll { it.beanType.name == 'test.Outer$Nested' }
        .size() == 1

    cleanup:
    context.close()
}
```

The end-to-end check is in micronaut-validation: with the guard fixed,
`ArchiveCompiler` can name every nested type of a TCK deployment and
`:micronaut-tests:micronaut-jakarta-validation-tck:jakartaTck` compiles all deployments.

## What it unblocks

The 11 `xmlconfiguration.constraintdeclaration.containerelementlevel` tests,
`valueextraction.builtin.JavaFXValueExtractorsTest` (5), the three
`integration.cdi.managedobjects` tests, and
`valueextraction.definition.ValueExtractorDefinitionTest#valuePassedToExtractorRetrievedFromHost`.

## Out of scope

A constraint declared only in a `META-INF` XML mapping, on a type the build never saw, cannot produce a
compile-time introspection at all — no fix to the guard changes that. It belongs in the documentation:
reflection-free XML mapping requires `@Introspected` on the mapped types.
