# Declared-only metadata for parameters, and inherited type-argument annotations

**Status: proposal** (drafted 2026-11-07 from micronaut-validation `codex/validation-core53-hardening` 88ce0908)
**Component:** micronaut-core `core-processor` (`ParameterElement`, `AbstractAnnotationMetadataBuilder`), `inject-java`
**Checked against:** micronaut-core `origin/5.3.x`

## The gap

#12917 gave methods a declared-only view (`getDeclaredMethodAnnotationMetadata()`). Parameters have none:
`AbstractAnnotationMetadataBuilder.lookupOrBuildForParameter` merges the overridden methods' parameters into one
flat metadata and `ParameterElement` has no declared accessor. Type arguments of a parameter or return type
(`List<@NotNull T>`) are `ClassElement`s outside that hierarchy, so their annotations are not inherited by an
overriding method at all.

Jakarta Validation needs both: an override must not add parameter constraints (spec 5.6.5), which needs what
the override declares itself; and the container-element constraints of the overridden method apply to the
override.

micronaut-validation works around it in the processor: `ValidationVisitor` snapshots each parameter's declared
type-use annotations into a synthetic `DeclaredParameters` annotation before core merges, rebuilds `Argument`s
from it at runtime (`ValidationMetadataSupport.declaredArguments`), and copies constraint annotations onto type
arguments of overriding methods (`inheritAnnotationsForMethod`/`ForParameter`).

## Proposed API

```java
// core-processor
interface ParameterElement {
    /** The annotations this declaration carries itself, before the overridden declarations are merged in. */
    AnnotationMetadata getDeclaredAnnotationMetadata();
}
```

At runtime the parameter metadata is written as a two-level hierarchy `[inherited, declared]`, so that
`Argument.getAnnotationMetadata().getDeclaredMetadata()` answers the declaration's own annotations while
`getAnnotationMetadata()` stays merged. When building the parameters of an overriding method, the builder also
inherits the annotations of the matching type arguments of the overridden parameter and return types.

## What micronaut-validation deletes

`ValidationMetadataSupport.TYPE_USE`/`DECLARED_PARAMETERS`/`declaredArguments`/`typeUse`, the processor
snapshot and `declaredTypeUse`, `inheritAnnotationsForMethod`/`ForParameter` (~130 lines and a synthetic
annotation on every constrained method).

## Risk

Generated metadata grows for overriding methods. Inheriting type-argument annotations changes behaviour for
every module reading them (serde, data) - probably the expected behaviour, but worth a release note.
