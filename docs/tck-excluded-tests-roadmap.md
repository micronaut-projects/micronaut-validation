# Jakarta Validation TCK: What The Excluded Tests Need

Captured: 2026-08-23

Updated: 2026-08-23 — the causes that could be closed without micronaut-core are closed. 20 of the 113
introspection-profile exclusions are gone; the section on each cause records what landed and what is left.

Branch: `claude/core-reflection-bridge` (micronaut-validation) over `claude/reflection-bridge` (micronaut-core)

The part of this addressed to micronaut-core is summarised on its own in `micronaut-core-work.md`.

## Where things stand

The TCK runs three ways, from `tests/jakarta-validation-tck/build.gradle`:

| Task | Suite | What it proves | Exclusions |
| --- | --- | --- | --- |
| `jakartaTck` | `tck-spec-tests.xml` | generated introspections supplemented by the reflection bridge | **none** |
| `jakartaTckIntrospection` | `tck-introspection-tests.xml` | generated introspections only (`micronaut.validation.reflection.enabled=false`) | 93 methods / 37 classes (was 113) |
| `jakartaTckReflection` | `tck-reflection-tests.xml` | an archive compiled without the annotation processor, described reflectively (`micronaut.validation.tck.processor.enabled=false`) | 14 methods / 6 classes |

The full profile is already green with no exclusions, so nothing below is a compliance gap of
`micronaut-validation-jakarta`. What is left is a gap of each *half* of the stack taken on its own:

- the **introspection profile** is what a reflection-free Micronaut application gets. Everything excluded
  there is metadata the annotation processor does not emit today.
- the **reflection profile** is what a library that never ran the annotation processor gets. Everything
  excluded there needs a Micronaut *bean definition*, not validation metadata.

Both exclusion lists were re-measured on 2026-08-23 by running exactly the excluded methods: 113/113 and
13/13 failed, so the lists were neither stale nor conservative. After the work recorded below, 20 of the 113
pass and are no longer excluded. (The 14th reflection-profile entry is a
package-level exclusion — `integration.cdi.executable` fails at deployment, before any method runs.)

### Reproducing

Point a TCK task at `tck-spec-tests.xml` and the failures are exactly these lists. For one class or method:

```bash
./gradlew -Dorg.gradle.jvmargs=-Xmx4g -Dmicronaut.validation.reflection.enabled=false :micronaut-tests:micronaut-jakarta-validation-tck:singleJakartaTck -PtckSingleClass=org.hibernate.beanvalidation.tck.tests.metadata.ExecutableDescriptorTest
```

## The introspection profile: five causes, 113 tests

"Tests" is what each cause accounted for when the list was first measured, "left" what is still excluded.

| # | Cause | Tests | Left | Lives in |
| --- | --- | --- | --- | --- |
| 1 | A generated introspection describes one constructor | 43 | 43 | micronaut-core (+ 2 need constructor interception) |
| 2 | Generated metadata does not say which type declared what | 39 | 19 | **20 fixed here**; the rest needs micronaut-core |
| 3 | A field and its getter are one property with one metadata | 7 | 7 | micronaut-core |
| 4 | Only `@Executable` methods are described | 4 | 4 | fixed for users, blocked in the TCK harness by micronaut-core |
| 5 | The type has no generated introspection at all | 20 | 20 | blocked by micronaut-core |

### 1. Constructors are not described (43 tests)

`BeanIntrospection` exposes a single `getConstructor()`, synthesised from `getConstructorArguments()` — the
constructor it instantiates beans with. The specification names constructors by their parameter types and
describes each one: parameters, cross-parameter constraints, return value, cascading and group conversions.
`ReflectiveIntrospection.getConstructors()` is what makes the full profile pass.

Symptom: `BeanDescriptor.getConstraintsForConstructor(...)` returns `null`, then the TCK NPEs.

Implement in **micronaut-core**:

- `BeanIntrospection.getConstructors()` returning every declared constructor, `getConstructor()` first —
  the same contract `ReflectiveIntrospection` already documents.
- `BeanIntrospectionWriter` emits one `BeanConstructor` per declared constructor, each with its own
  `AnnotationMetadata` and its `Argument[]` (parameter annotations and type-use annotations included).
  Today only the bean-instantiating constructor's arguments reach the introspection.
- A knob so this is not paid for by every introspection — e.g. `@Introspected(constructors = true)`, set by
  `IntrospectedValidationIndexesVisitor` when the type has constrained constructors.

Then in **micronaut-validation**, two call sites collapse to the new API and the reflective special case goes:
`IntrospectedBeanDescriptor.constructors()` (validation/src/main/java/io/micronaut/validation/validator/IntrospectedBeanDescriptor.java:296)
and `ReflectiveExecutables.beanConstructor` (validation/src/main/java/io/micronaut/validation/validator/ReflectiveExecutables.java:94).
`ValidationVisitor.visitConstructor` must also run for every constructor, not only the one the TCK harness
hands it.

Unblocks: `metadata.ExecutableDescriptorTest` (17), `metadata.CrossParameterDescriptorTest` (6),
`metadata.BeanDescriptorTest` (5), `metadata.ReturnValueDescriptorTest` (4),
`metadata.ParameterDescriptorTest` (3), `methodvalidation.ValidateConstructorReturnValueTest` (2),
`constraints.application.method.MethodValidationRequirementTest#testReturnValueConstraintsAreDeclaredByAnnotatingConstructors`,
`constraints.validatorresolution.ValidatorResolutionTest#testTargetedTypeIsConstructor`,
`constraints.crossparameter.InvalidDeclarationOfGenericAndCrossParameterConstraintTest#testConstraintTargetParametersOnConstructorWithoutParametersCausesException`,
`constraints.groups.groupconversion.InvalidGroupDefinitionsTest#testGroupConversionWithoutValidAnnotationOnConstructorReturnValue`.

**Two of the 43 need more than metadata**: `integration.cdi.executable.ExecutableValidationTest#testReturnValueValidationOfConstrainedConstructor`
and `integration.cdi.executable.types.ExecutableTypesTest#testValidationOfConstrainedConstructorReturnValueWithExecutableTypeCONSTRUCTORS`
invoke a constrained constructor and expect the container to have validated it. Micronaut AOP intercepts
methods, not constructors. Treat these as a separate, larger core item — or as a documented boundary.

### 2. The declaring type of a declaration is lost (39 tests)

The specification distinguishes what a type declares from what it inherits: `ElementDescriptor.declaredOn`,
`findConstraints().lookingAt(Scope.LOCAL_ELEMENT)`, the implicit group of the declaring type, and the rules
that make a declaration *illegal* (a parameter constrained in two parallel interfaces, a return value
cascaded in both an interface and its implementation). Generated metadata merges all of it into one
`AnnotationMetadata` — `ValidationVisitor.inheritAnnotationsForMethod` deliberately copies the annotations
of overridden methods down — so "declared here" and "inherited" are indistinguishable.
`ExecutableHierarchy.declaredBy` (validation/src/main/java/io/micronaut/validation/validator/ExecutableHierarchy.java:126)
shows the split: a `ReflectiveIntrospection` answers with `findDeclaredMethod`, a generated one falls back to
filtering `getBeanMethods()` by declaring type — which also requires the super type or interface to have an
introspection of its own, and often it has none.

Symptoms: `ConstraintInheritanceTest` finds 1 constraint where 2 are declared; the whole
`invaliddeclarations` package throws nothing where a `ConstraintDeclarationException` is required;
`ContainerElementTypeDescriptorTest` finds no property at all when every constraint sits on an interface
getter's type arguments.

**What landed (20 tests).** Route (b), the cheap half of it: the validator reads the declarations back from
the introspections of the super types, which it already walks for class-level constraints
(`ValidatorDeclarations.superIntrospections`). No new generated metadata was needed.

- `IntrospectedBeanDescriptor.IntrospectedPropertyDescriptor` gained `superProperties()`: the property as each
  introspected super type declares it. Its constraints, cascades, group conversions and container element
  types are attributed to the type declaring them — an interface puts its constraints in the implicit group of
  the interface — instead of being folded into the metadata of the type inheriting them. The declared-wins
  filter in `constraintDescriptorsByKey` no longer hides an inherited constraint behind a locally declared
  one, and `Scope.LOCAL_ELEMENT` now has something to exclude.
- `ExecutableHierarchy.Resolved.parallel()` no longer requires every inherited declaration to be *exact*. Each
  declaration is already read from the introspection of the type declaring it, so two of them mean two
  branches of the hierarchy, whether or not the metadata of each is free of what it inherits.
- `ValidationVisitor` marks a constrained method `@Executable`, so it becomes a bean method of the
  introspection and the super-type declarations are visible to `ExecutableHierarchy.declaredBy` at all.

Unblocked: `InvalidMethodConstraintDeclarationTest` (7 of 9), `ElementDescriptorTest` (4 of 5),
`ContainerElementTypeDescriptorTest` (3 of 4), `ConstraintInheritanceTest` (2 of 3),
`InvalidGroupDefinitionsTest` (2 of 5), `InvalidGroupDefinitionsOnContainerElementsTest` (2 of 4).
`IntrospectionOnlySpec` covers the behaviour outside the TCK.

**What is left (19 tests)** all need what a super-type introspection cannot give:

- The *return value* half of the declaration rules (`testGroupConversionGivenOnReturnValue…`,
  `testReturnValueIsMarkedAsCascaded…`) needs a declaration whose metadata is genuinely local. Marking a
  generated bean method as an exact declaration was tried and measured: it fixes two more tests and breaks
  seven, because Micronaut's metadata already folds the annotations of an overridden method into the
  overriding one, so "declared" reads as more than the type declares. Separating them needs either a
  declared-only `AnnotationMetadata` view that keeps repeatable containers (today
  `getDeclaredMetadata()` drops them) or the per-declaration metadata of cause 2 route (a).
- `testValidationConsidersConstraintsFromSuperTypes` needs the *validation* path to visit a property once per
  declaring level, the way `DefaultValidator.visitProperty` does for a `ReflectiveIntrospection`. That needs a
  per-level value read, which is cause 3.
- `GroupTest#testImplicitGrouping`, `ConstraintDescriptorTest#testGetGroupsWithImplicitGroup` and
  `GroupSequenceIsolationTest#testCorrectDefaultSequenceInheritance3` need the implicit group on the
  *validation* path as well as on the descriptors.

### 3. A field and its getter are one property (7 tests)

A generated `BeanProperty` merges the field and the getter into one member, reads through the getter, and
reports one metadata. The specification treats them as two constrained elements: a constraint on the field is
validated against the field's value, a constraint on the getter against the getter's, the traversable
resolver is told `ElementType.FIELD` or `METHOD`, and the two sets of constraints add up.
`ReflectiveIntrospection.PropertyMember` is the shape the validator already consumes for this
(`DefaultValidator.visitPropertyMember`, validation/src/main/java/io/micronaut/validation/validator/DefaultValidator.java:1099).

Implement in **micronaut-core**: a per-member view of a generated property — element type, declaring type,
its own metadata, its own read accessor — so `@Introspected(accessKind = {FIELD, METHOD})` stops collapsing
the two. The declaration half of this overlaps with cause 2 and could ride the same side table; the *value*
half (reading the field, not the getter) cannot: it needs a generated field accessor.

Unblocks: `traversableresolver.TraversableResolverTest` (3),
`constraints.application.ValidationRequirementTest#testConstraintAppliedOnFieldAndProperty` and `#testFieldAccess`,
`metadata.ElementDescriptorTest#testGetConstraintDescriptors`,
`validation.ValueAccessStrategyTest#testValueFromFieldIsPassedToValidatorOfFieldLevelConstraint`.

### 4. Only `@Executable` methods are described (4 tests)

`BeanIntrospection.getBeanMethods()` lists the methods marked `@Executable`. A method descriptor must exist
for every constrained method even when its execution is *not* intercepted — `@ValidateOnExecution(NONE)`
turns interception off, not metadata.

**What landed.** `ValidationVisitor` now marks every constrained method `@Executable`, so a plain
`@Introspected` type has method descriptors at all. Without it
`getConstraintsForMethod` returns `null` for a constrained method of an ordinary Micronaut bean — a gap the
TCK harness hid, because it marks every method executable itself. `IntrospectionOnlySpec` covers it.

**Why the four TCK tests still fail.** They need the harness to describe a method while *not* intercepting
it. The harness expresses "do not intercept" as `@Vetoed`, which removes the method from every element query,
the introspection included. Replacing that with `@Executable` plus removing `RequiresValidation` from the
method was tried and measured: twelve `integration.cdi.executable` tests then fail, because
`DefaultElementBeanDefinitionBuilderFactory` selects the methods to advise with
`ElementQuery.ALL_METHODS.annotated(am -> am.hasAnnotation(RequiresValidation))`, and the metadata of a
`MethodElement` combines the class and the method — so the class-level `RequiresValidation` that
`ValidationVisitor` adds makes *every* method of the type match. Until that query reads the declared method
metadata, "described but not intercepted" cannot be expressed. That query is micronaut-core.

### 5. The type has no generated introspection (20 tests)

These fail with `Bean introspection not found for the class: ...`. The types are
`ManagedValueExtractorsTest$Foo`, `ContainerElementTypeConstraintsForFieldXmlMappingTest$FishTank`,
`ContainerElementTypeIgnoreAnnotationsMappingTest$OrderField`,
`JavaFXValueExtractorsTestImpl$BasicPropertiesEntity` — verified by keeping a deployment directory and
listing the generated classes: no `$Introspection` is written for any of them.

The cause is now known exactly: the annotation processor visits the types a round hands it — the top level
ones, and the ones carrying an annotation of their own. A nested type whose constraints live only in an XML
mapping carries no annotation, so it is never visited and never introspected.

**Why it is blocked.** Both ways of naming such a type from outside — `@Introspected(classNames = …)` and
`@ClassImport(classNames = …)` on a generated `Application` class — were implemented and measured. Both bring
the missing types in (the TCK goes from 1054 to 1067 executed tests) and both then fail the build: for a
handful of types the introspection is generated twice and the second
`visitServiceDescriptor` call throws `Unable to generate Bean entry at path: …BeanIntrospectionReference/…`.
Instrumenting `TestClassVisitor` shows the imported element visited more than once, and
`IntrospectedTypeElementVisitor.isIntrospected` cannot see the first generation because a class imported from
elsewhere is named `$<originating>$<Simple>$Introspection` rather than the `$<Simple>$Introspection` it looks
for. Dropping `AggregatingTypeElementVisitorProcessor` from the harness changes nothing. Making the import
idempotent is micronaut-core.

Separately, and independent of that: a constraint declared only in `META-INF` XML on a type the build never
saw cannot produce a compile-time introspection at all. That part is a real limitation to document —
reflection-free XML mapping requires `@Introspected` on the mapped types.

## The reflection profile: 13 tests, one boundary

Every failure is the absence of a Micronaut *bean definition*:

- 11 tests in `integration.cdi.executable.*` invoke a method and expect the container to have validated it.
  Micronaut interception is a compile-time proxy; without the annotation processor there is no proxy and the
  call goes straight through. `ValidationInterceptorPriorityTest` additionally asserts the interceptor's
  priority, which only exists as generated metadata.
- `integration.cdi.factory.ConstraintValidatorInjectionTest` and `integration.ee.cdi.ConstraintValidatorInjectionTest`
  need `@Inject` into a `ConstraintValidator`. The validator instance is created (the harness's
  `BeanContextConstraintValidatorFactory` does that reflectively) but its collaborator stays null, so
  `isValid` throws.

Closing these would mean runtime bean definitions and runtime proxying in micronaut-core — a different
product, not a validation feature. **Recommendation: do not roadmap these.** State in
`tck-reflection-tests.xml` and in the user documentation that the reflective description covers *validation
metadata* and that interception and injection remain compile-time features. That is already what the suite
comment says; it just needs to read as a decision rather than a to-do.

## Suggested order

1. ~~**Cause 4**~~ — the product half landed; the TCK half needs the core query fix below.
2. ~~**Cause 2, the super-type half**~~ — landed, 20 tests.
3. **Cause 1, metadata half** (41 tests) — `BeanIntrospection.getConstructors()` in core plus the writer
   change. Biggest remaining lever, and it deletes two `ReflectiveIntrospection` special cases in the validator.
4. **Cause 2, the rest** (19 tests) — a declared-only `AnnotationMetadata` view that keeps repeatable
   containers, or per-declaration metadata; then the validation path per declaring level.
5. **Cause 3** (7 tests) — per-member properties in core, including a generated field accessor.
6. **Cause 5** (20 tests) — idempotent import/introspection generation in core, plus a documented XML limitation.
7. **Cause 4, the TCK half** (4 tests) — make the advice query in
   `DefaultElementBeanDefinitionBuilderFactory` read the declared method metadata rather than the class and
   method combined. This also stops every method of a type with one constrained method from being advised.
8. **Cause 1, interception half** (2 tests) — constructor interception in core, or a documented boundary.
9. **Reflection profile** (13 tests) — documentation, not implementation.

Everything remaining needs micronaut-core.
