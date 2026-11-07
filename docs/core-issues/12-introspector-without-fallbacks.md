# Ask a BeanIntrospector for generated introspections only

**Status: proposal** (drafted 2026-11-07 from micronaut-validation `codex/validation-core53-hardening` 88ce0908)
**Component:** micronaut-core `core` (`BeanIntrospector`, `BeanIntrospection`)
**Checked against:** micronaut-core `origin/5.3.x`

## The gap

Once `micronaut-reflection` is on the class path, its `BeanIntrospectionFallback` answers every lookup, and
there is no way to ask for compile-time introspections only. micronaut-validation needs that when
`micronaut.validation.reflection.enabled=false`, and wraps the introspector in `GeneratedBeanIntrospector`
(127 lines): a per-class-loader index of `BeanIntrospectionReference` names, a same-name-other-loader check
(now redundant since #14278), and a filter on `AbstractInitializableBeanIntrospection`.

## Proposed API

```java
public interface BeanIntrospector {
    /** This introspector without its fallbacks: only the introspections generated at compile time. */
    default BeanIntrospector withoutFallbacks() { return this; }
}
public interface BeanIntrospection<T> {
    /** Whether the introspection was generated at compile time rather than built by a fallback. */
    default boolean isGenerated() { return false; }
}
```

`DefaultBeanIntrospector` already keeps references and fallbacks apart (`getFallbacks`), so this is a view.

## What micronaut-validation deletes

`GeneratedBeanIntrospector`, the `generatedOnly` flag of `CompileTimeSupport` (~140 lines).
