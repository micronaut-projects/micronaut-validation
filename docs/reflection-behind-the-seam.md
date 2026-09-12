# Reflection behind the seam

Where `micronaut-validation` reads a class, and where it stopped. Every number here is measured by making a
path throw and counting the failures of `:micronaut-validation:test`, whose classpath carries no reflection
module and which therefore runs on `CompileTimeSupport` alone.

## What moved

| Read | Sites | Answered instead by |
| --- | --- | --- |
| What a container binds in a super type, and which argument an extractor extracts | 4 | the extractor's own index, which the signature walk falls back to anyway |
| The `ConstraintValidator` signature of a validator | 2 | the introspection of the validator |
| Constraint definition rules over an annotation type | 8 | nothing; the reflection module, or a clear error |
| The no-argument constructor of a validator the container does not build | 3 | nothing; the reflection module, or a clear error |
| The `ValueExtractor` signature of a registered instance | 4 | nothing; the reflection module, or a clear error |
| The parameter names of an executable | 1 | nothing; the reflection module, or a clear error |
| The validators a constraint declares | 2 | the occurrence, which the processor records |
| The report-as-single-violation marker | 1 | the occurrence, which the processor now retains |
| The validation target a validator declares | 2 | the introspection of the validator |
| Whether a group is a group sequence | 1 | the introspection of the group |
| Whether a repeatable container holds a constraint | 1 | the occurrence, which retains the contract |

The validator holds no reflective cache: the two that keyed a `ConcurrentHashMap` by `Class` moved into
`micronaut-validation-reflection` with the reads they served.

Two processor changes carry most of the weight. Constraint validators are now introspected, so what a
validator validates and the target it declares are read from metadata rather than from the class. The
report-as-single-violation marker is retained on the occurrences of a constraint that declares it.

Failures without the reflection module went from 61 to none: the validation suite passes whole on a
classpath that has no reflection module. All three TCK profiles stayed at 1054, 1019 and 1019 throughout,
with no failures.

## What stayed, and why

Eleven call sites remain, and none of them is a metadata read:

- `getSuperclass` and `getInterfaces` walks in `ValidatorDeclarations`, `ExecutableHierarchy`,
  `DefaultValidator` and `DefaultConstraintValidatorContext`. These ask a class what it extends. They load no
  annotations and no members, and they need no native-image configuration. The NoReflection check reports
  `getInterfaces` as `INTERFACES`, so the four classes are allowed it by name in `validation/build.gradle`;
  `getSuperclass` it does not report. Making them require the module is not
  an option either way: `ValidatorDeclarations` accounts for 106 tests and `ExecutableHierarchy` for 78,
  because they are how constraint inheritance and method hierarchies are resolved at all.
- Two matches are false positives of the inventory grep: `BeanDefinition.getConstructor` and
  `ConstraintDescriptor.getAnnotation` are Micronaut APIs, as are the two `AnnotationMetadata.getAnnotation`
  calls in `ValueExtractorDefinition`.


## Bindings made one level up

A type argument bound at an intermediate generic super type is the case core
[#13081](https://github.com/micronaut-projects/micronaut-core/pull/13081) fixed for bean definitions: a walk that
matches the super type by its raw type, without carrying the bindings of the levels between, loses it. Two of the three
walks of the reflection module had the same defect.

- The validated type of a constraint validator was read off the first parameterized super class and given up on there,
  so a cross-parameter validator validating `String` through an abstract base was accepted by the strict definition
  check.
- The type argument a container passes on to the one an extractor extracts was looked for one level up only, so a
  type swapping its arguments through a base read a map's value from the wrong argument.

Both now go through `ReflectionGenericArguments`, whose walk carries the bindings of every level and which was right
already. `IntermediateBindingSpec` holds the shapes, with the same shapes bound directly beside them.

## Registering a value extractor without its class being read

The specification registers a value extractor as an instance and says nothing else about it, so
`ValidatorContext.addValueExtractor(ValueExtractor)` has no choice but to read the `ValueExtractor`
signature the class declares. That signature is the one thing about an extractor that no annotation
processor can have recorded, because the registration happens at runtime.

`MicronautValidatorContext` takes the description instead: the container type, the type of the value, which
type argument carries it and whether it is unwrapped by default, as a `ValueExtractorDefinition`.
`DefaultValidatorConfiguration` and the context `DefaultValidatorFactory.usingContext()` returns both accept
it, so an application that does not want the reflection module can register an extractor and say what it
extracts. The specification's own signature still works and still reads the class, which needs the module.

## A validator the container builds

Introspecting every constraint validator made the introspection the first place the validator factory looks,
which is wrong for a validator that takes its dependencies through its constructor: the introspection can
name it but not build it. Such a validator falls to the bean registration, which supplies them. Without that,
any injected `@Singleton` validator fails with "No default constructor exists".

## The core gap, closed

`BeanIntrospection` used to record nothing about the type arguments a class binds in its interfaces, while
`BeanDefinition` recorded exactly that. That was filed as
[`core-issues/08-introspection-interface-type-arguments.md`](core-issues/08-introspection-interface-type-arguments.md)
and micronaut-core now carries `BeanIntrospection.getTypeArguments`.

With it, the type a validator validates is the second argument its introspection records for
`ConstraintValidator`, so the internal `@ConstraintValidatorTypes` annotation that stood in for it is gone,
along with the six hand-written declarations of it. The processor visitor that wrote it now only introspects
the validator, which is what makes the type arguments readable in the first place.

The six built-in validators still carry `@Introspected` by hand: the processor that would add it depends on
this module and cannot be run over its own sources.
