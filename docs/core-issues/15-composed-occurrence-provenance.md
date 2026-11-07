# Record which annotation introduced a composed constraint occurrence

**Status: proposal** (drafted 2026-11-07 from micronaut-validation `codex/validation-core53-hardening` 88ce0908)
**Component:** micronaut-core `core-processor` (`AbstractAnnotationMetadataBuilder`), `core` (`AnnotationValue`)
**Checked against:** micronaut-core `origin/5.3.x`

## The gap

When two composed constraints on one element each compose `@Size`, the merged metadata flattens the
occurrences under `Size$List` and loses which composing annotation introduced each one. Jakarta Validation
reports composing constraints per composed constraint, with `reportAsSingleViolation` and
`@OverridesAttribute(constraintIndex)` read per composition (spec 3.3).

micronaut-validation reconstructs it: the processor reads javac `AnnotationMirror`s directly
(`ValidationAnnotationRemapper.JavaComposition`), falls back to declared names for Kotlin/Groovy
(`retainDeclaredCompositions`), records `$directComposingConstraints`/`$compositionError`, and the reflection
module re-derives it in `ReflectedComposition` (287 lines).

## Proposed API

Per stereotype occurrence, the introducing annotation:

```java
public class AnnotationValue<A> {
    /** The annotation whose declaration introduced this occurrence as a stereotype, or empty for one declared on the element. */
    public Optional<String> getIntroducedBy();
}
```

or the retained stereotype tree carried by the generated `AnnotationBuilder` of the composing annotation.

## What micronaut-validation deletes

`ReflectedComposition`, `JavaComposition`, `retainDeclaredCompositions`, `hasDirectAndContainerComposition`,
`ConstraintDefinitionMetadata.compositionError` (~300 lines), and the Java/Kotlin/Groovy difference.
