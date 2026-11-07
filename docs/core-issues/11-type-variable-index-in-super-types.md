# Which type variable a type passes to a super type, from one source

**Status: proposal** (drafted 2026-11-07 from micronaut-validation `codex/validation-core53-hardening` 88ce0908)
**Component:** micronaut-core `core` (`TypeHierarchy`, `TypeHierarchies`)
**Checked against:** micronaut-core `origin/5.3.x`

## The gap

"Which of my type variables does `S<…>` receive as argument *i*, or is it bound?" is answered by
micronaut-validation three ways, one per source of type information:
`ContainerTypeArguments.generatedIndex` (introspection `getTypeArguments`), `ContainerTypeArguments.recordedIndex`
(`$TypeHierarchy` records), and `ReflectionGenericArguments` (reflection). `CompileTimeSupport` adds a fourth
copy for bound arguments, and `ValidationAnnotationUtil.POSITIONAL_JDK_CONTAINERS` hard-codes the JDK
collections nothing records.

Jakarta value extractors are declared on a container type (`ValueExtractor<Map<@ExtractedValue ?, ?>>`); the
validator needs the index of the extracted argument on the validated type, which may be a subclass binding
some arguments (`class Names extends HashMap<String, @NotNull Name>`).

## Proposed API

```java
// io.micronaut.core.type
public sealed interface TypeHierarchy {
    /** The super type as declared, its arguments unsubstituted. */
    Optional<Argument<?>> getSuperType(Class<?> superType);
    /** Which own type variable an argument of a super type is, empty when it is bound. */
    OptionalInt indexOfVariable(Argument<?> superTypeArgument);
}
// TypeHierarchies.find(Class) also answers from BeanIntrospection.getTypeHierarchy() and, through a
// @Indexed TypeHierarchyFallback SPI, from the reflection module; core records the java.util collections once.
```

## What micronaut-validation deletes

`ContainerTypeArguments`, `ReflectionContainerTypeArguments`, `ReflectionGenericArguments`, the bound-argument
methods of `CompileTimeSupport` and `ReflectionSupport`, `POSITIONAL_JDK_CONTAINERS` (~400 lines).

## Other users

micronaut-serde (custom container types), micronaut-data (`Page<T>`/`Slice<T>` subclasses).
