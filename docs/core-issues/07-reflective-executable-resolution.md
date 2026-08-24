# Resolving a `java.lang.reflect` handle to Micronaut metadata is written once per project

**Component:** `micronaut-core` — `inject` (`io.micronaut.inject.reflection`)
**Found on:** `claude/reflection-bridge`, from the micronaut-validation work and a review of
[micronaut-jaxrs#641](https://github.com/micronaut-projects/micronaut-jaxrs/pull/641)
**Unblocks:** no TCK methods directly — it stops the second consumer of the bridge from writing the same
three helpers again

## Summary

The bridge answers "describe this `Class`". It does not answer the two questions that every consumer asks
immediately afterwards:

1. **Given a `java.lang.reflect.Method` or `Constructor`, what is the best Micronaut metadata for it?** Not
   "reflect on it" — a bean definition may already describe it, or a `BeanMethod` of the introspection may,
   and either beats reflection.
2. **Given a method, what does it override or implement, and what did each level declare?** Any
   specification with annotation-inheritance rules needs this, and it cannot be answered from one
   `BeanIntrospection`.

micronaut-validation answered both while replacing its reflective validator. Both answers are written
entirely in core types and contain no Bean Validation concept. micronaut-jaxrs needs the same two answers the
moment it has a reflective path, and today would write them a second time.

## Current behaviour

Four classes in `micronaut-validation`, none of which mention `jakarta.validation` (except where noted):

| Class | Lines | Imports outside `io.micronaut.*` |
| --- | ---: | --- |
| `validator/ReflectiveExecutables.java` | 152 | `java.lang.reflect`, `java.util` only |
| `validator/IntrospectedExecutableMethod.java` | 63 | `java.lang.reflect.Method` only |
| `validator/ExecutableHierarchy.java` | 486 | 5 `jakarta.validation` imports, all in the rule half |
| `validator/metadata/ConfiguredMetadata.java` | 67 | none |

`ReflectiveExecutables` is the resolution strategy:

```java
static <T> ExecutableMethod<T, Object> executableMethod(ExecutionHandleLocator locator,
                                                        BeanIntrospector introspector,
                                                        Method method) {
    // the bean definition of the declaring type, if the method is declared by that exact type
    // else the BeanMethod of the introspection
    // else a ReflectionExecutableMethod over the java.lang.reflect.Method
}

static <T> BeanConstructor<T> beanConstructor(@Nullable BeanIntrospection<T> introspection,
                                              Constructor<T> constructor) { … }

static Argument<?>[] constructorArguments(BeanIntrospection<?> introspection, Constructor<?> constructor) { … }
```

`IntrospectedExecutableMethod` is the adapter that strategy needs in the middle branch: an
`ExecutableMethod` over a `BeanMethod`, invoking through the introspection rather than through reflection.

`ExecutableHierarchy` is the traversal:

```java
static Resolved resolve(BeanIntrospector introspector, Declaration local, String name);
```

It walks the super classes and then every interface, once each, asks each level's introspection what it
declares — `ReflectiveIntrospection.findDeclaredMethod` when the introspection is reflective, the matching
`BeanMethod` when it is generated — and returns the local declaration, the exact declaration of the
declaring type when it is known, the list of inherited declarations, and the three merged views (annotation
metadata, parameters, return value) with the validated level winning.

## Why it matters

Both projects implement "what did this method inherit", against two different metadata models, and neither
can use the other's:

- **micronaut-jaxrs** does it at compile time. `JaxRsTypeElementVisitor` iterates `overriddenMethods` over
  `MethodElement` (lines 656 and 1085) to apply Jakarta REST §3.6 annotation inheritance.
- **micronaut-validation** does it at runtime. `ExecutableHierarchy.resolve` walks super types through
  `BeanIntrospector` to apply Jakarta Validation §5.6.5 declaration rules.

A JAX-RS resource compiled without the Micronaut processor has neither a `MethodElement` nor a bean
definition, so a reflective JAX-RS profile needs the runtime traversal — the one that already exists here.
The same holds for the resolution strategy: three sites in `jaxrs-reflection` reflect over methods and
constructors directly today (`JaxRsReflectionProviderInstantiator`,
`JaxRsReflectionClientComponentInstantiator`, `JaxRsReflectionSeBootstrapProvider`), and each would be a line
or two if `ReflectiveExecutables` were in core.

The plan that consumes this is [`../jaxrs-reflection-refactoring-plan.md`](../jaxrs-reflection-refactoring-plan.md),
items W2, W4 and W5.

## Proposed change

### 1. Move the resolution strategy as-is

`ReflectiveExecutables` → `io.micronaut.inject.reflection.ReflectionExecutables`, and
`IntrospectedExecutableMethod` → `io.micronaut.inject.reflection.IntrospectedExecutableMethod`. Both compile
in core unchanged; every type they touch (`ExecutionHandleLocator`, `BeanIntrospector`, `BeanMethod`,
`BeanConstructor`, `AbstractBeanConstructor`, `AnnotationReflectionUtils`,
`ReflectionAnnotationMetadataBuilder`, `ReflectionExecutableMethod`, `ReflectiveIntrospection`) is already
there. This is a move, not a redesign, and it is the half that unblocks jaxrs W4 and W5.

Make them public rather than package-private, and keep `@Experimental` alongside the rest of the bridge.

### 2. Split the hierarchy

Core takes the traversal:

- the `Declaration` record — `declaringType`, `annotationMetadata`, `arguments`, `returnArgument`, `exact` —
  with its two factories, `of(ExecutableMethod)` and `of(BeanMethod, boolean exact)`
- `Resolved` — `local`, `declared`, `inherited`, and the merged `annotationMetadata` / `arguments` /
  `returnArgument`
- `Key` — the identity of an executable, for caching
- `resolve`, `inherited`, `collectInterfaceDeclarations`, `declaredBy`, `mergeArgument`, `merge`,
  `declaredOf`

micronaut-validation keeps the rules, as static functions over those records instead of methods on them:

- the four predicates currently declared *on* `Declaration` — `hasParameterConstraintsOrCascades`,
  `hasCascadedReturnValue`, `hasParameterGroupConversions`, `hasReturnValueGroupConversions` — become
  `ValidationDeclarations.hasParameterConstraintsOrCascades(Declaration)` and so on. They are the only reason
  the record would otherwise have to move with Bean Validation attached
- `checkParameterDeclarations` / `checkReturnValueDeclarations` on `Resolved` become static checks taking a
  `Resolved`
- `checkGroupConversions`, `constraintNames`, `groupConversions`, `hasCascadedReturnConflict`,
  `addsParameterConstraints`, `parallel`, `isConstrainedOrCascaded`, `isCascaded`, `hasGroupConversions`

Roughly 180 of the 486 lines move; the five `jakarta.validation` imports all stay behind.

**One design decision to make while moving it.** `declaredBy` skips a super type by package name:

```java
if (typeName.startsWith("java.") || typeName.startsWith("jakarta.")) {
    return Optional.empty();
}
```

Skipping `jakarta.` is right for Bean Validation — the API interfaces carry nothing a validator needs — but
wrong in general: a JAX-RS resource implementing a `jakarta.ws.rs`-annotated interface would be skipped, and
§3.6 says its annotations are inherited. In core this should be a parameter, or narrowed to `java.`,
`javax.` and `jdk.`, with the caller passing anything further it wants ignored.

### 3. `ConfiguredMetadata.merge` last, or not at all

It merges a list of `AnnotationMetadata` into one. Core already has `AnnotationMetadataHierarchy`, which is
what the current implementation delegates to; the value is the null/empty handling around it. If it moves, it
belongs as a static factory on `AnnotationMetadataHierarchy` rather than as a new class. Sixty-seven lines
duplicated is not a real cost, so this is optional.

## Tests

For the resolution strategy, in `inject`:

- a method of a type with a bean definition resolves to the bean definition's `ExecutableMethod`, not to a
  reflective one — assert the concrete class
- a method of an introspected type without a bean definition resolves through the `BeanMethod`
- a method of a type with neither resolves to `ReflectionExecutableMethod`
- a method declared by a super type resolves against the type that declares it, not against a sub type that
  merely inherits it — this is the case that made validation's
  `methodReturnValueValidationTargetsReturnValueAndCascadedConstraints` fail, and it is easy to regress
- a constructor of an introspected type resolves to the introspection's `BeanConstructor`; one of a
  non-introspected type to an anonymous `AbstractBeanConstructor` over the reflective metadata

For the traversal:

- a class implementing two unrelated interfaces that both declare the same method: `inherited` has both, in
  a stable order, and `exact` is true for each
- a class extending a class that implements an interface: all three levels appear once, the interface once
  only even when reachable by two paths
- `declared` is the declaration of the type itself when the introspection can say so, and falls back to the
  local declaration when it cannot — the `exact` flag distinguishes them, and the validation rules depend on
  that distinction
- merged annotation metadata reads the validated level first
- the package-skip parameter: a super type in a skipped package is absent; the same type is present when the
  caller does not skip it

The existing regression suite is the validation TCK: `jakartaTckIntrospection` exercises the traversal
heavily, so a mistake in the split shows up as a number rather than as a subtle behaviour change.

## What it unblocks

No TCK method, in either project. What it prevents is a second copy: three call sites in `jaxrs-reflection`
that would otherwise reimplement the resolution strategy, and a JAX-RS §3.6 implementation at runtime that
would otherwise reimplement the traversal that Jakarta Validation §5.6.5 already needed.

Sequencing: step 1 is a move and can land immediately. Step 2 is worth doing before micronaut-jaxrs starts
its reflective path, and after the six issues above it — those change what generated introspections contain,
and the traversal reads generated introspections.
