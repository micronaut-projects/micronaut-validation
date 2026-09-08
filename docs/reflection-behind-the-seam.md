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

The validator holds no reflective cache: the two that keyed a `ConcurrentHashMap` by `Class` moved into
`micronaut-validation-reflection` with the reads they served.

Two processor changes carry most of the weight. Constraint validators are now introspected, so what a
validator validates and the target it declares are read from metadata rather than from the class. The
report-as-single-violation marker is retained on the occurrences of a constraint that declares it.

Failures without the reflection module went from 61 to 3 over the course of this work, while all three TCK
profiles stayed at 1054, 1019 and 1019 with no failures.

## What stayed, and why

Twelve call sites remain, and nine of them are not metadata reads at all:

- `getSuperclass` and `getInterfaces` walks in `ValidatorDeclarations`, `ExecutableHierarchy`,
  `DefaultValidator` and `DefaultConstraintValidatorContext`. These ask a class what it extends. They load no
  annotations and no members, they need no native-image configuration, and the reflection checkstyle
  configuration deliberately does not list them among reflective calls. Making them require the module is not
  an option either way: `ValidatorDeclarations` accounts for 106 tests and `ExecutableHierarchy` for 78,
  because they are how constraint inheritance and method hierarchies are resolved at all.
- Three matches are false positives of the inventory grep: `BeanDefinition.getConstructor`,
  `ConstraintDescriptor.getAnnotation` and `AnnotationMetadata.getAnnotation` are Micronaut APIs.

One genuine read is left. `ConstraintContainers` loads the constraint a repeatable container holds and asks
the class whether it is a constraint. Reading that from the occurrence's stereotypes was tried and reverted:
an occurrence nested inside a repeatable container does not carry the constraint contract, and the attempt
broke six tests across three suites. Retaining the contract on contained occurrences in the processor is the
way to close it, in the same shape as the report-as-single-violation marker.

## What still needs the module

Three tests, all handing something to the specification API that nothing generated describes:

- two register a `ValueExtractor` instance through `Configuration.addValueExtractor`, so only the instance's
  class says what it extracts
- one uses a `@Singleton` validator declared in a Groovy test source, which the Java annotation processor
  does not reach, so it carries no introspection

They need a home: a test task carrying the reflection module, in the shape of the existing `elTest` task, or
a move into the suite of `micronaut-validation-reflection`.

## The open core gap

`BeanIntrospection` records nothing about the type arguments a class binds in its interfaces, while
`BeanDefinition` records exactly that. Until that is closed, the six constraint validators of this module
declare `@ConstraintValidatorTypes` and `@Introspected` by hand, because the processor that would write them
depends on the module and cannot be run over its own sources. See
[`core-issues/08-introspection-interface-type-arguments.md`](core-issues/08-introspection-interface-type-arguments.md).
