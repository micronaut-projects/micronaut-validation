# Introspector caching, override lookup outcomes and metadata identity

**Status: proposed** (drafted 2026-11-18 from micronaut-validation `claude/adopt-core53-merges` 1dd13864)
**Component:** micronaut-core `core`, `inject`
**Checked against:** micronaut-core `origin/5.3.x` 4ae43cea11

Four small gaps found while removing per-call work from micronaut-validation.

## A cached introspector per class loader

`BeanIntrospector.forClassLoader(loader)` returns a new `DefaultBeanIntrospector` on every call, which loads the
introspection service registry again. Validation calls it from several entry points that cannot share an
instance easily (the EL interpolator, its message bundle loader, the Jakarta bootstrap configuration, the default
methods of `ReflectionSupport`), and now routes them through the support discovered per loader instead.
Proposal: `BeanIntrospector.forClassLoader` answers one instance per loader (held weakly, like
`SoftServiceLoader` caches), or a separate `BeanIntrospector.shared(ClassLoader)` for callers that want it.

## `findOverridden` cannot tell "unknown" from "not overridden"

`BeanTypeHierarchy.findOverridden(introspector, level, name, parameterTypes)` returns empty both when the level
declares no such method and when the generated hierarchy does not know the method at all (a method that is not
a bean method, made known later by an XML mapping or a decorating introspection). Validation would have to
check `getDeclaringTypes(name, types)` first to decide whether to fall back to a scan of the bean methods of the
level. Proposal: a tri-state result (`Optional<Optional<BeanMethod>>` or a small enum plus method), or a
`knowsMethod(name, parameterTypes)` on the hierarchy, documented next to `findOverridden`.

## Memoized `getDeclaredMetadata()`

`AnnotationMetadataHierarchy.getDeclaredMetadata()` (and the hierarchies a bean property builds) creates a new
instance per call. Callers that compare metadata by identity - validation's constraint descriptors do, so that
a descriptor built twice for the same element is equal - get unequal descriptors for `Scope.LOCAL_ELEMENT`
unless they cache the result themselves (`IntrospectedBeanDescriptor.declaredMetadata`). Proposal: the
hierarchy computes its declared metadata once and returns the same instance.

## `addDeclaredAnnotation` merges a repeated annotation silently

`MutableAnnotationMetadata.addDeclaredAnnotation(name, values)` called twice for the same annotation merges the
members of the second call into the first, so two `@Pattern` occurrences an XML mapping declares on one element
became one with the members of both. Validation now moves both occurrences into the repeatable container
itself (`XmlMappingParser.addConstraint`, which has to remove and re-add the defaults). Proposal: an
`addDeclaredAnnotation(AnnotationValue<?>)` that turns a second occurrence of a repeatable annotation into a
container entry, or at least reports the merge, and a public way to add an annotation value with its
stereotypes (validation still writes `AnnotationUtil.STEREOTYPES_MEMBER` by hand in
`ValidationMetadataSupport`).
