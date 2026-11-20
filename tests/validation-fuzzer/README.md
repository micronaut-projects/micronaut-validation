# Differential validation fuzzer

Generates random constrained beans and values, validates them with Micronaut Validation and with Hibernate
Validator (the Jakarta Validation reference implementation), and reports every difference.

## What it generates

Each case is a small graph of `@Introspected` beans: classes with field or getter constraints, records,
subclasses overriding getters with extra constraints, nested beans and cascades (`@Valid` on properties and on
type arguments, `@ConvertGroup`), containers (`List`, `Set`, `Map`, `Optional`, arrays, nested containers) with
type-argument constraints, all built-in constraints with random attributes, groups (`G1`, `G2 extends G1`, `G3`,
the sequence `Seq`, redefined default group sequences), payloads, message templates (attributes, bundle keys,
EL, escapes, malformed templates) and custom constraints: a class-level constraint building custom violations
with property, bean and container element nodes, a field constraint, a constraint whose default message comes from
a bundle, and composed constraints with `@OverridesAttribute`, with and without `@ReportAsSingleViolation`.
A few cases knowingly carry an invalid definition or a constraint on an unsupported type; their signatures are
tagged `[invalid-definition]` / `[unsupported-type]`.

The sources are compiled in process with the Micronaut annotation processors. Each instance is checked with
`validate` for several group combinations, `validateProperty` and `validateValue` for each root property, and
each bean type with `getConstraintsForClass`. Violations are compared by path (node kinds, names, indexes,
keys) and constraint type, then by template, message, invalid value, leaf bean, path string, node container
details and constraint descriptor.

Micronaut is the validator of an application context, the default `micronaut-validation` module, with a
`ResourceBundleMessageSource` for `ValidationMessages`: Jakarta EL is not on the classpath, so differences in
messages using `${...}` are expected and marked `(EL template)`.

## Running

```
./gradlew :micronaut-tests:micronaut-validation-fuzzer:fuzz -Pfuzz.seed=1 -Pfuzz.batches=20 -Pfuzz.batchSize=40 -Pfuzz.minimize=40
```

The report is written to `tests/validation-fuzzer/build/fuzz-report/report.md`: a table of difference signatures
with the number of cases showing each, examples with the sources and instance, and, with `fuzz.minimize`, a
minimized reproducer for the first example of each coarse group of signatures.

Replay a single case, keeping its generated sources under `build/fuzz-report/work`:

```
./gradlew :micronaut-tests:micronaut-validation-fuzzer:fuzz -Pfuzz.seed=1 -Pfuzz.batchSize=40 -Pfuzz.replay=3:23
```

`ReproducersTest` holds hand-written reproducers of the differences found so far; each fails while its
difference remains:

```
./gradlew :micronaut-tests:micronaut-validation-fuzzer:reproducers
```
