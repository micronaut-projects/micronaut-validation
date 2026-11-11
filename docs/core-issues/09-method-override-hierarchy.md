# Resolve the override hierarchy of a bean method from introspections

**Status: adopted** (core #14321, merge 6b26fabb6a; adopted 2026-11-11)
**Component:** micronaut-core `core` (`BeanTypeHierarchy`), `inject`, `reflection` (`MethodHierarchy`)
**Checked against:** micronaut-core `origin/5.3.x`

## The gap

Core can answer which types declare a method (`BeanTypeHierarchy.getDeclaringTypes(BeanMethod)`), but not what
each of those declarations says. The one implementation that does - `reflection/MethodHierarchy` - walks
`Class.getSuperclass()`/`getInterfaces()`/`getDeclaredMethods()` and so needs reflection.

micronaut-validation therefore keeps a non-reflective copy, `validator/ExecutableHierarchy` (~450 of its 712
lines), and `ReflectionValidationSupport` converts `MethodHierarchy.Declaration` to
`ExecutableHierarchy.Declaration` field by field. Jakarta Validation reads the constraints of every level of a
method's hierarchy (spec 5.6.5).

The copy also had to learn what the reflective walk gets for free: a generic super declaration is matched by
erasure. `interface Repo<T> { void save(@NotNull T t); }` declares `save(Object)`, which
`StringRepo.save(String)` implements; matching by exact parameter classes misses it and silently drops the
inherited constraint (fixed in validation by `ExecutableHierarchy.erasesTo`).

## Proposed API

```java
// io.micronaut.core.beans
public interface BeanTypeHierarchy {
    /** The declarations of a method by the levels of the hierarchy, the nearest first, read from their introspections. */
    List<BeanMethodDeclaration> getMethodDeclarations(BeanMethod<?, ?> method);

    /** The bean method a level declares that a method of these parameter types overrides: exact signature first, else the erasure of a generic declaration. */
    Optional<BeanMethod<?, ?>> findOverridden(Class<?> level, String name, Class<?>... parameterTypes);
}

public record BeanMethodDeclaration(Class<?> declaringType, AnnotationMetadata annotationMetadata,
                                    List<Argument<?>> arguments, Argument<?> returnArgument, boolean exact) { }
```

`MethodHierarchy` moves from `reflection` to `inject` (the merge needs `AnnotationMetadataHierarchy`), resolves
from introspections first and falls back to the reflective walk only for a level without one.

## What micronaut-validation deletes

`ExecutableHierarchy` resolve/merge/records (keeps the ~230 lines of spec checks), `ReflectionSupport`
`resolveHierarchy`/`superType`/`interfaces`/`canResolveHierarchy`, `CompileTimeSupport.resolveHierarchy`,
`ReflectionValidationSupport.resolveHierarchy`/`toCore`/`fromCore`, `ValidationMetadataSupport.declaringLevels`.

## Other users

micronaut-data (repository methods inherited from generic interfaces - the erasure case), micronaut-http
(`@Controller` methods inheriting routes from an interface), micronaut-serde (annotations on interface getters).

## Risk

`BeanTypeHierarchy` and `MethodHierarchy` are `@Experimental`. The merged metadata must stay the
`AnnotationMetadataHierarchy(true, ...)` shape validation already relies on.
