# Remaining restricted TCK cases

Investigation against validation commit `b3027cc000e7e78d7a67365367110e45d2f01412`
and core `5.3.0-SNAPSHOT`, on 2026-10-01. The generated-description profile disables
reflective supplementation; it still has the optional Jakarta dependencies on its
classpath. It is distinct from the tests that physically exclude reflection modules.

All seven exclusions were run explicitly and failed. They reduced to three causes. The
first is fixed: core #13624 processes every repeated `@ClassImport`, and on 2026-10-07 the
constructor case passed in the full restricted profile (1,048 tests), so its exclusion is
removed. Six remain:

| Test class | Excluded methods | Cause |
| --- | --- | --- |
| `xmlconfiguration.groupconversion.GroupConversionTest` | `testGroupConversionsAppliedOnConstructor`, `testGroupConversionsAppliedOnField`, `testGroupConversionsAppliedOnGetter`, `testGroupConversionsAppliedOnMethod` | XML maps private `Groups.convert(String)`, which has no generated callable method metadata. All four operations fail while loading the same mapping. |
| `xmlconfiguration.methodvalidation.IgnoreAnnotationsOnMethodTest`, `xmlconfiguration.methodvalidation.IgnoreAnnotationsInMethodConfigurationTest` | `testIgnoreAnnotationsOnMethodLevel`, `testIgnoreAnnotationsOnReturnValueParameterAndCrossParameter` | XML maps private `IgnoreAnnotations.foobar(String, String)`, also absent from generated callable method metadata. |

The six XML cases report `ValidationException: Unknown method in validation XML`.
They pass with the optional provider. Their absence from callable introspections is
a current capability boundary, not evidence that core should invoke private methods.
Removing these exclusions would require core declaration metadata that can describe
inaccessible methods independently of invocation. Validation must not generate extra
declaration classes, substitute a different method, or silently ignore these mappings.

## Corrected constructor diagnosis

The earlier claim that core omits the enclosing-instance parameter was incorrect.
Standard core processors generate both `this$0` and the explicit constrained parameter
in source and binary-import reproductions. No constructor generator change is indicated.

The TCK harness emits repeated imports for utility packages followed by the metadata
package. `VisitorUtils.collectImportedElements` reads `getAnnotation(ClassImport.class)`
once, although `ClassImport` is repeatable. Consequently the metadata package import,
including `CustomerService.InnerClass`, is ignored.

A core-only reproduction, without validation processors or visitors, confirms this:

```java
@ClassImport(classNames = {"example.First"}, annotate = {Introspected.class})
@ClassImport(classNames = {"example.Second"}, annotate = {Introspected.class})
class Imports {}
```

Only `First` gets an introspection. Swapping annotation order makes only `Second`
get one. The reproduction also works with a non-static inner class as `Second`.
The fix must consume every occurrence while respecting its import settings.
Core #13624 (5.3.x) consumes every occurrence; the TCK case passes against it.

## Other confirmed core defects

These do not account for the remaining exclusions, but were independently reproduced or
identified during the same investigation:

* `ArgumentExpUtils` resolves a generic placeholder before reading its occurrence
  type-use metadata. An inherited `List<@NotNull T>` property and
  `Container<List<@NotNull T>>` supertype lose the nested annotation when `T` resolves
  to `String`. An unannotated occurrence must stay unannotated.
* `MicronautMetaServiceLoaderUtils` holds a static cache entry with a strong reference
  to the most recently queried application class loader. Closing its context does
  not release that reference; querying another loader replaces it. This is bounded
  retention of the latest loader, not an accumulating cache leak.

All three are fixed in core 5.3.x: repeated imports by #13624, the generic metadata
loss by #13625 and the class-loader retention by #13626. With #13625 validation no
longer records its own type-use snapshots; the three TCK profiles pass without them
(verified 2026-10-07).

## Reproduction and acceptance

For the constructor case:

```shell
./gradlew :micronaut-tests:micronaut-jakarta-validation-tck:singleJakartaTck \
  -PtckSingleClass=org.hibernate.beanvalidation.tck.tests.metadata.ExecutableDescriptorTest \
  -PtckSingleMethod=testGetParameterDescriptorsForConstructorOfInnerClass \
  -Dmicronaut.validation.reflection.enabled=false
```

Use the other class and method names from the table to reproduce each XML case.
Before removing exclusions, run `jakartaTckIntrospection` with
`-PtckIntrospectionSuite=tck-spec-tests.xml` and classify every failure.
Acceptance remains 1,054 passing full-stack cases and 1,048 passing restricted cases,
with six explicit exclusions rather than reported skips.
