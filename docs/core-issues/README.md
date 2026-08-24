# micronaut-core issues behind the remaining TCK exclusions

One file per issue, each written to stand on its own: what is missing, the evidence, what to implement, and
tests to write. The analysis they come from is `../tck-excluded-tests-roadmap.md`; the short version for
core is `../micronaut-core-work.md`.

All of it is measured on micronaut-validation `claude/core-reflection-bridge` over micronaut-core
`claude/reflection-bridge`. The full TCK profile (`jakartaTck`) passes with no exclusions, so none of these
is a compliance gap of `micronaut-validation-jakarta` — each is something one half of the stack cannot do
alone.

| Issue | Tests | Size |
| --- | --- | --- |
| [01 — a bean introspection describes only one constructor](01-bean-introspection-constructors.md) | 41 | medium |
| [02 — no declared-only view of `AnnotationMetadata`](02-declared-only-annotation-metadata.md) | 19 | small–medium |
| [03 — a bean property merges a field and its getter](03-per-member-bean-properties.md) | 7 | medium |
| [04 — an imported type is introspected twice](04-duplicate-introspection-for-imported-types.md) | 20 | small |
| [05 — validation advice matches every method](05-validation-advice-matches-every-method.md) | 4 + a live defect | small |
| [06 — constructors are not intercepted](06-constructor-interception.md) | 2 | large |
| [07 — reflective executable resolution is written once per project](07-reflective-executable-resolution.md) | — | medium |

Issue 05 is the one to read first even without the TCK in mind: it is a defect in its own right, and small.

Issue 07 is the odd one out: it unblocks no TCK method. It is a duplication that only becomes visible with a
second consumer of the reflection bridge, and micronaut-jaxrs is about to be one — see
[`../jaxrs-reflection-refactoring-plan.md`](../jaxrs-reflection-refactoring-plan.md).

Not filed, deliberately: the 14 methods `jakartaTckReflection` excludes. Every one of them needs a Micronaut
bean definition — compile-time interception, or `@Inject` into a `ConstraintValidator` — and closing them
would mean runtime bean definitions and runtime proxying. The recommendation is to document the boundary
instead: the reflective description covers validation *metadata*; interception and injection stay
compile-time features.

## Verifying a fix

```bash
./gradlew -Dorg.gradle.jvmargs=-Xmx4g :micronaut-tests:micronaut-jakarta-validation-tck:jakartaTckIntrospection
```

Point the task at `tck-spec-tests.xml` instead of `tck-introspection-tests.xml` and the failures are exactly
the exclusion list, so the effect of a change is a number. For one class or method use `singleJakartaTck`
with `-PtckSingleClass=…` and `-PtckSingleMethod=…`. For a one-minute loop over the same behaviour without
the TCK, run `:micronaut-validation:test --tests '*IntrospectionOnlySpec'`.
