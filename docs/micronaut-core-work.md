# What micronaut-core Would Have To Implement

Captured: 2026-08-23

Companion to `tck-excluded-tests-roadmap.md`. That document analyses every Jakarta Validation TCK test the
two half-stack profiles still exclude; this one is the part addressed to micronaut-core, with the evidence
for each item and what it would unblock.

Each item is also written up on its own, issue-ready and with tests to add, in `core-issues/`.

Everything here is measured on branch `claude/core-reflection-bridge` over micronaut-core
`claude/reflection-bridge`. The full profile (`jakartaTck`) passes with no exclusions, so none of this is a
compliance gap of `micronaut-validation-jakarta` — it is what each half of the stack cannot do alone:

- **`jakartaTckIntrospection`** — the generated introspections with the reflection bridge turned off, which
  is what a reflection-free Micronaut application gets. 93 methods excluded.
- **`jakartaTckReflection`** — an archive compiled without the annotation processor, which is what a library
  that never ran it gets. 14 methods excluded.

The causes that could be closed inside micronaut-validation already have been (20 tests, commit
`57e4e081`). Everything below needs core.

| # | What | Tests | Size |
| --- | --- | --- | --- |
| 1 | `BeanIntrospection.getConstructors()` | 41 | medium |
| 2 | A declared-only view of `AnnotationMetadata` | 19 | small–medium |
| 3 | Per-member bean properties: a field and its getter apart | 7 | medium |
| 4 | Idempotent introspection generation for imported types | 20 | small |
| 5 | The validation advice query reads the declared method metadata | 4 (+ a real over-advising bug) | small |
| 6 | Constructor interception | 2 | large |
| — | Runtime bean definitions (the reflection profile) | 14 | not recommended |

## 1. `BeanIntrospection.getConstructors()` — 41 tests

**Missing.** `BeanIntrospection` exposes one constructor,
`getConstructor()` (`core/src/main/java/io/micronaut/core/beans/BeanIntrospection.java:369`), synthesised from
`getConstructorArguments()` — the constructor the introspection instantiates beans with. Jakarta Validation
names constructors by their parameter types and describes each: parameters, cross-parameter constraints,
return value, cascading, group conversions.

**Evidence.** `BeanDescriptor.getConstraintsForConstructor(...)` returns `null` and the TCK then NPEs on
`ConstructorDescriptor`. The contract that makes the full profile pass is already written down in
`inject/src/main/java/io/micronaut/inject/reflection/ReflectiveIntrospection.java`:
`List<BeanConstructor<T>> getConstructors()`, "every constructor of the type, `getConstructor()` first".

**Implement.**

- Promote `getConstructors()` from `ReflectiveIntrospection` to `BeanIntrospection`, same contract.
- `BeanIntrospectionWriter` emits one `BeanConstructor` per declared constructor, each with its own
  `AnnotationMetadata` and its `Argument[]` — parameter annotations and type-use annotations included. Today
  only the bean-instantiating constructor's arguments reach the introspection.
- Gate it so every introspection does not grow: an `@Introspected` member, set by
  `IntrospectedValidationIndexesVisitor` when the type has constrained constructors.

**On the validation side** two call sites then collapse to the new API and the reflective special case goes:
`IntrospectedBeanDescriptor.constructors()` and `ReflectiveExecutables.beanConstructor`.

**Unblocks.** `metadata.ExecutableDescriptorTest` (17), `metadata.CrossParameterDescriptorTest` (6),
`metadata.BeanDescriptorTest` (5), `metadata.ReturnValueDescriptorTest` (4),
`metadata.ParameterDescriptorTest` (3), `methodvalidation.ValidateConstructorReturnValueTest` (2), and four
single tests in `MethodValidationRequirementTest`, `ValidatorResolutionTest`,
`InvalidDeclarationOfGenericAndCrossParameterConstraintTest` and `InvalidGroupDefinitionsTest`.

## 2. A declared-only view of `AnnotationMetadata` — 19 tests

**Missing.** No way to ask an element for the annotations *it* declares, separately from the ones it
inherits, while keeping repeatable containers intact.

- `MethodElement.getMethodAnnotationMetadata()` documents itself as "annotations defined on a method **or
  inherited from the super methods**" (`core-processor/.../inject/ast/MethodElement.java:52`), and
  `getAnnotationMetadata()` additionally folds in the class.
- `AnnotationMetadata.getDeclaredMetadata()` returns a `DefaultAnnotationMetadata` built from
  `declaredAnnotations`/`declaredStereotypes` with `allAnnotations` and `allStereotypes` nulled
  (`inject/.../annotation/DefaultAnnotationMetadata.java:168`), which is why
  `ExecutableHierarchy.declaredOf` narrows an `AnnotationMetadataHierarchy` but returns any other metadata
  unchanged.

**Evidence.** `ExecutableHierarchy.Declaration` already carries an `exact` flag for exactly this, and the
declaration rules branch on it. Marking a generated bean method as an exact declaration was implemented and
measured: **two more tests pass and seven regress**, all with false `ConstraintDeclarationException`s
("Parameter constraints cannot be added in overriding or implementing methods"), because the overriding
method's metadata already contains what the interface declared.

**Implement.** A reliable declared-only view — either a narrowing that preserves the repeatable-container
lookup, or per-declaration metadata retained on the generated `BeanMethod`/`BeanProperty`/`Argument`.

**Unblocks.** The return-value half of the declaration rules — `InvalidGroupDefinitionsTest` (3),
`InvalidGroupDefinitionsOnContainerElementsTest` (2), `InvalidMethodConstraintDeclarationTest` (2),
`ValidMethodConstraintDeclarationTest`, `MethodValidationTest` (2), `CrossParameterDescriptorTest` (2
`lookingAt`), `InvalidDeclarationOfGenericAndCrossParameterConstraintTest` (2 interface cases) — plus the
implicit-group tests in `GroupTest`, `ConstraintDescriptorTest` and `GroupSequenceIsolationTest`.

## 3. Per-member bean properties — 7 tests

**Missing.** A generated `BeanProperty` merges the field and the getter into one member, reads through the
getter, and reports one metadata. `BeanProperty.getDeclaringType()` returns the *bean* type
(`core/src/main/java/io/micronaut/core/beans/BeanProperty.java:295`), not the type declaring the member.

Jakarta Validation treats them as two constrained elements: a constraint on the field is validated against
the field's value and one on the getter against the getter's, the traversable resolver is told
`ElementType.FIELD` or `METHOD`, and the two sets add up.

**Implement.** The shape already exists as `ReflectiveIntrospection.PropertyMember` — element type,
declaring type, own metadata, own argument, own read accessor. Generated introspections need the same, so
`@Introspected(accessKind = {FIELD, METHOD})` stops collapsing the two. The *declaration* half overlaps with
item 2; the *value* half does not — reading the field rather than the getter needs a generated field
accessor.

**Unblocks.** `traversableresolver.TraversableResolverTest` (3),
`ValidationRequirementTest#testConstraintAppliedOnFieldAndProperty` and `#testFieldAccess`,
`ElementDescriptorTest#testDeclaredOn`,
`ValueAccessStrategyTest#testValueFromFieldIsPassedToValidatorOfFieldLevelConstraint`, and
`ConstraintInheritanceTest#testValidationConsidersConstraintsFromSuperTypes`, which needs the validation path
to visit a property once per declaring level.

## 4. Idempotent introspection generation for imported types — 20 tests

**Missing.** The annotation processor visits the types a round hands it: the top-level ones, and the ones
carrying an annotation of their own (`roundEnv.getRootElements()` plus `getElementsAnnotatedWith`). A nested
type whose constraints live only in an XML mapping carries no annotation, so it is never visited and never
introspected — the validator then fails with `Bean introspection not found for the class: …`.

Naming such a type from outside is supposed to be the answer, and both mechanisms exist:
`@Introspected(classNames = …)` and `@ClassImport(classNames = …)`.

**Evidence.** Both were implemented in the TCK harness and measured. Both bring the missing types in — the
suite goes from 1054 to 1067 executed tests — and both then fail the build: for a handful of types the
introspection is generated twice and the second `visitServiceDescriptor` throws
`Unable to generate Bean entry at path: META-INF/micronaut/io.micronaut.core.beans.BeanIntrospectionReference/…`
(the `Filer` refusing to recreate a resource). Instrumenting the visitor shows the imported element visited
more than once. `IntrospectedTypeElementVisitor.isIntrospected`
(`core-processor/.../beans/visitor/IntrospectedTypeElementVisitor.java:101`) cannot see the first generation,
because it looks for `<package>.$<Simple>$Introspection` while a class imported from elsewhere is written as
`$<originating>$<Simple>$Introspection`. Removing `AggregatingTypeElementVisitorProcessor` from the harness
changes nothing, so it is not the two-processor registration.

**Implement.** Make the guard cover imported introspections — key it on the target class rather than on the
generated name, or record the generated service descriptors already written for the round.

**Unblocks.** The 11 `xmlconfiguration…containerelementlevel` tests, `JavaFXValueExtractorsTest` (5), the
three `integration.cdi.managedobjects` tests and
`ValueExtractorDefinitionTest#valuePassedToExtractorRetrievedFromHost`.

**Not fixable even then**, and worth documenting: a constraint declared only in `META-INF` XML on a type the
build never saw cannot produce a compile-time introspection. Reflection-free XML mapping requires
`@Introspected` on the mapped types.

## 5. The validation advice query reads the declared method metadata — 4 tests, and a bug

**Missing.** `DefaultElementBeanDefinitionBuilderFactory` selects the methods to advise with

```java
target.getEnclosedElements(ElementQuery.ALL_METHODS.annotated(am -> am.hasAnnotation(ANN_REQUIRES_VALIDATION)))
```

(`core-processor/.../processing/definition/DefaultElementBeanDefinitionBuilderFactory.java:294`). The
predicate receives the `MethodElement`, whose `getAnnotationMetadata()` combines the class and the method.
`ValidationVisitor` puts `@RequiresValidation` on the class as soon as *any* member needs validation, so the
query matches **every** method of the type.

**Evidence.** This is a live over-advising bug: a type with one constrained method has all its methods
advised. It also makes "described but not intercepted" inexpressible, which the specification requires —
`@ValidateOnExecution(NONE)` turns interception off, not metadata. Expressing it in the TCK harness was
implemented and measured: twelve `integration.cdi.executable` tests then fail because the class-level marker
re-advises the methods whose method-level marker was removed. `DeclaredBeanElementCreator.makeInterceptedForValidationIfNeeded`
gets this right — it uses `hasDeclaredAnnotation`.

**Implement.** Narrow the predicate to the declared method metadata, the way
`makeInterceptedForValidationIfNeeded` does.

**Unblocks.** `ExecutableDescriptorIgnoresValidatedExecutableAnnotationSettingsTest` (2),
`BeanDescriptorTest#testGetConstrainedMethodsTypeGETTER` and `#testGetConstrainedMethodsTypesGETTERAndNON_GETTER`.

## 6. Constructor interception — 2 tests

Micronaut AOP intercepts methods, not constructors. `ExecutableValidationTest#testReturnValueValidationOfConstrainedConstructor`
and `ExecutableTypesTest#testValidationOfConstrainedConstructorReturnValueWithExecutableTypeCONSTRUCTORS`
invoke a constrained constructor and expect the container to have validated it. This is a large piece of core
work on its own, and reasonable to leave as a documented boundary.

## What not to do: the reflection profile — 14 tests

Every one of these needs a Micronaut *bean definition*, not validation metadata:

- 11 tests in `integration.cdi.executable.*` invoke a method and expect the container to have validated it.
  Micronaut interception is a compile-time proxy; without the annotation processor there is no proxy and the
  call goes straight through. `ValidationInterceptorPriorityTest` additionally asserts the interceptor's
  priority, which only exists as generated metadata. (One of the 14 is a package-level exclusion: the
  deployment fails before any method runs.)
- `integration.cdi.factory.ConstraintValidatorInjectionTest` and `integration.ee.cdi.ConstraintValidatorInjectionTest`
  need `@Inject` into a `ConstraintValidator`. The instance is created but its collaborator stays null, so
  `isValid` throws.

Closing these means runtime bean definitions and runtime proxying — a different product. The recommendation
is to state the boundary in the documentation: the reflective description covers *validation metadata*;
interception and injection remain compile-time features.

## Verifying any of it

```bash
./gradlew -Dorg.gradle.jvmargs=-Xmx4g :micronaut-tests:micronaut-jakarta-validation-tck:jakartaTckIntrospection
```

Point the task at `tck-spec-tests.xml` instead and the failures are exactly the exclusion list, so the effect
of a change is a number. For one class or method use `singleJakartaTck` with `-PtckSingleClass=…` and
`-PtckSingleMethod=…`; for a one-minute loop over the same behaviour without the TCK, run
`:micronaut-validation:test --tests '*IntrospectionOnlySpec'`.
