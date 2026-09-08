# A bean introspection does not record the type arguments a bean binds in its interfaces

**Status: closed.** micronaut-core carries `BeanIntrospection.getTypeArguments`, and micronaut-validation
reads what a validator validates from it.

**Component:** `micronaut-core` — `core` (`BeanIntrospection`), `core-processor` (`BeanIntrospectionWriter`)
**Found on:** micronaut-core `5.2.0-SNAPSHOT`, measured from micronaut-validation `reflection-behind-seam-wip`

The index of these files lives on `claude/core-reflection-bridge-docs`; this one was filed from the branch
that found it.

## What the gap was

`BeanDefinition` recorded what a bean binds the type arguments of a super type to, and `BeanIntrospection`
recorded nothing of the kind. `getGenericBeanType()` carried only the type parameters the bean type itself
declares. So a lookup keyed by a bare `Class` could learn what that class binds only when it happened to be
a container bean.

For one class, compiled with both `@Introspected` and `@Singleton`:

```java
class Test implements ConstraintValidator<NotNull, CharSequence> { … }
```

| Call | Result then |
| --- | --- |
| `definition.getTypeArguments("…ConstraintValidator")` | `[NotNull A, CharSequence T]` |
| `definition.getTypeArguments()` | `[]` |
| `introspection.getGenericBeanType().getTypeParameters()` | `[]` |
| `introspection.asArgument().getTypeParameters()` | `[]` |

Jakarta Validation picks the `ConstraintValidator` for a value by the second type argument of
`ConstraintValidator<A, T>`, and micronaut-validation has to answer that from a bare `Class` in three places
where no `BeanDefinition` is at hand: resolving among the classes a constraint's `@Constraint(validatedBy)`
names, checking a class returned by a user-supplied `ConstraintValidatorFactory`, and describing a validator
the container does not build. Each read the class's generic interfaces to do it.

## How it is answered now

`BeanIntrospection.getTypeArguments(Class)` returns the same list the bean definition returns, so the type a
validator validates is the second argument its introspection records for `ConstraintValidator`. The
annotation processor of micronaut-validation introspects every constraint validator, without which there is
no introspection to read.

The internal `@ConstraintValidatorTypes` annotation that stood in for this is gone, with the six hand-written
declarations of it the built-in validators carried.

## Still worth confirming

`BeanDefinition.getTypeArguments()` with no argument returns empty while the named overload returns the
arguments. That may be defined as the first generic super type rather than any interface, in which case only
the documentation is at fault.
