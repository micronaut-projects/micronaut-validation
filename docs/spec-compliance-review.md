# Review of `spec-compliance` against the no-reflection principles

Branch reviewed: `claude/el-compile-time-messages` = PR #631 (`spec-compliance`, 107 files, +21 403 −305)
plus the compile-time EL commits. Merge base `b314c593` on `5.2.x`.

Principles the review is held against:

1. No reflection unless the TCK leaves no other way.
2. No XML, JNDI, JavaFX or other rarely used machinery outside the modules the TCK needs them in.
3. Declaration rules, definition rules and group ordering computed once, at compilation time, with the
   element model of micronaut-core — extending core where it falls short.
4. Where reflection must stay, it produces the same `Argument`, `AnnotationMetadata`, `ExecutableMethod`
   and `BeanIntrospection` the processor would have produced, through one shared bridge that
   micronaut-jaxrs #641 uses too.

## What was measured

| Measurement | Result |
|---|---|
| Jakarta Validation 3.1.1 TCK, full Jakarta profile, Glassfish EL replaced by micronaut-expression-language | **1054 / 1054** |
| Same run with `micronaut-validation-reflection` excluded from the TCK runtime classpath | **405 failures** |
| Lines of `validation-reflection` main sources | 8 430, of which `ReflectionValidator.java` is 5 868 |
| Files touching `java.lang.reflect` or reflective calls in main sources | 29 (56 call sites in `ReflectionValidator`, 28 in `XmlValidationMetadataProvider`, 10 in the core `DefaultConstraintDescriptor`) |

The 405 is the number that matters. It is what the reflection module carries today, not what reflection is
needed for. Broken down by TCK package:

| Tests | TCK area | What they exercise | Reflection-free path |
|---:|---|---|---|
| 119 | `metadata` | `getConstraintsForClass`: constructor, method, parameter, return value and container element descriptors | Yes — `BeanIntrospection` already carries the constructor and the `@Executable` methods with their argument metadata; `IntrospectedBeanDescriptor` does not build descriptors from them |
| 67 | `methodvalidation` | Parameter constraints not inherited from super classes, return value constraints inherited, cross parameter | Yes — `MethodElement.overrides`, `ElementQuery.ALL_METHODS` |
| 43 | `groups.groupconversion`, `validation.groupconversion`, `xmlconfiguration.groupconversion` | `@ConvertGroup` on parameters, return values, container elements, in hierarchies | Yes — type-use annotation metadata on type arguments, already walked by `ValidationVisitor` |
| 36 | `inheritance.method.invaliddeclarations`, `invalidconstraintdefinitions`, `crossparameter` | `ConstraintDeclarationException` / `ConstraintDefinitionException` for illegal declarations | Yes — the illegal shape is visible in the AST; the exception has to be thrown at validation time, so the processor records it and the validator throws it |
| 38 | `xmlconfiguration.*` | Constraints, cascades, group conversions declared in mapping XML | Partly — class names must be loaded by string; members can be bound to the introspection when one exists (the TCK's `TestClassVisitor` introspects every test class with `FIELD` and `METHOD` access) |
| 24 | `validation` | Field vs getter access, value passed to validators | Yes — `@Introspected(accessKind = FIELD)` |
| 11 + 9 + 9 | `application.method`, `containerelement`, `validatorresolution` | Cascaded constructor parameters, map key/value constraints, ambiguous validator resolution | Yes — argument type variables, `Argument.getTypeParameters()` |
| 5 | `valueextraction.builtin` (JavaFX) | "Bean introspection not found" for the holder type | Yes — an introspection gap in the harness, not a reflection need |
| 4 | `methodvalidation.parameternameprovider` | `ParameterNameProvider.getParameterNames(Method)` | No — the spec API is defined on `java.lang.reflect.Method`; names come from `Parameter.getName()` at runtime |
| 3 + 3 + 3 | `integration.cdi.*`, `traversableresolver` | Managed validators and extractors, `isReachable` call sequence | Yes — bean context |

Roughly 390 of the 405 are facts about source structure. The TCK compiles its archives with the Micronaut
annotation processor (`ArchiveCompiler` installs `TypeElementVisitorProcessor`, `TestClassVisitor` adds
`@Introspected` to every test class), so the processor sees every one of those classes. What it does not do
today is record what it saw.

## 1. Reflection only where the TCK forces it

### 1.1 The reflection module is a second validator, not a fallback

`ReflectionValidator` `extends DefaultValidator` and is `@Replaces(DefaultValidator.class)`
(`ReflectionValidator.java:131-133`). For an introspected bean it runs **both** validators and merges the
two violation sets by annotation name and path (`validateIntrospectedObject`, lines 219-243;
`mergeViolations`, line 1079; `ReflectionViolationKey.of`, lines 51-57). Which set is authoritative is
decided per type by five heuristics — `hasReflectionCascadedProperties`, `hasReflectionRequiredPropertyAccess`,
`hasReflectionRequiredConstraints`, `hasReflectionConstraintValidators`, `hasReflectionContainerElements` —
and an `UnexpectedTypeException` from the generated path is swallowed when reflection is "authoritative".

To make that possible the module re-implements, privately, the whole object model of the specification:
descriptors (`ReflectionBeanMetadata`, `ReflectionPropertyDescriptor`, `ReflectionMethodDescriptor`,
`ReflectionConstructorDescriptor`, `ReflectionParameterDescriptor`, `ReflectionReturnValueDescriptor`,
`ReflectionCrossParameterDescriptor`, `ReflectionContainerElementDescriptor`, `ReflectionConstraintDescriptor`),
seventeen `Path` and `Path.Node` records, `ReflectionConstraintViolation`, an `InterpolationContext`, a
`ReflectionConstraintValidatorContext` of 618 lines, and its own group sequence, group conversion and
declaration rules. None of it shares code with the generated path it duplicates.

Consequences:

- Every constraint validator on an introspected bean runs twice in the Jakarta profile — user validators
  included, with whatever side effects and cost they have. This is not a TCK-only path:
  `@Replaces(DefaultValidator.class)` applies to any application that depends on
  `micronaut-validation-jakarta`.
- Two implementations of the same rules drift. The merge key is annotation name plus path; a violation the
  two paths report with a different path node kind is reported twice, one they report with the same key but
  different constraint attributes is reported once.
- The module is 8 430 lines that have to be kept in step with `DefaultValidator` by hand.

The design the principles call for is the inverse: reflection produces **metadata**, and the one validator
consumes it. `ReflectionValidationMetadataProvider` (214 lines) already starts down that road — it builds a
`MutableAnnotationMetadata` from `getDeclaredAnnotations()` — but it feeds only class and property
annotations, so the validator falls back to `ReflectionValidator` for everything else. Section 4 describes
the bridge that finishes the job; with it, `validation-reflection` becomes a reflective
`BeanIntrospection` provider and a warning, and the 5 868-line class is deleted.

### 1.2 Reflection that crept into the lean core module

`micronaut-validation` is documented as reflection-free by default. The PR adds these to it:

| Where | What | Replacement |
|---|---|---|
| `DefaultConstraintDescriptor.composingConstraints` / `composingAnnotations` / `annotationValues` / `defaultValues` / `applyOverrides` (lines 281-430) | Reads the composing constraints of a custom constraint from `Class.getDeclaredAnnotations()`, invokes annotation members with `setAccessible(true)`, applies `@OverridesAttribute` | The composing constraints are stereotypes in the generated `AnnotationMetadata` (`getAnnotationValuesByStereotype`), with their values; `@OverridesAttribute` is a compile-time fact and belongs in an `AnnotationTransformer` next to `ValidationAnnotationRemapper`, writing the overridden value into the stereotype. `ConstraintMessageELVisitor` walks exactly this structure at compile time today; the two must read one shared piece of metadata rather than each reconstruct it |
| `ConstraintValidatorTargetResolver.findTargetType` (lines 130-158) | Walks `getGenericInterfaces()` / `getGenericSuperclass()` to find the `ConstraintValidator<A, T>` type argument | For a bean: `BeanDefinition.getTypeArguments(ConstraintValidator.class)`. For a class given by name (`validatedBy`, XML, `ServiceLoader`): `AnnotationReflectionUtils.resolveGenericToArgument(type, ConstraintValidator.class)`, which core already has and which the previous `DefaultInternalConstraintValidatorFactory.getBeanType` did not use either |
| `DefaultValidator` lines 1605-1614 | `ParameterizedType` / `TypeVariable` resolution of the extractor container type | `Argument.getTypeVariable` / `getTypeParameters()` on the argument the extractor was registered with |
| `DefaultParameterNameProvider` | `Executable.getParameters()` | Unavoidable: the spec interface is defined on `Method` and `Constructor`. Keep, but the default provider should be consulted only when the `ExecutableMethod` has no argument names, which it always has |
| `DefaultValidatorConfiguration.argumentOf` / `annotationMetadataOf` (lines 519-560) | `AnnotatedType` to `Argument` for `ServiceLoader` value extractors | Pre-existing, not introduced by the PR. Should become the shared bridge of section 4 |

### 1.3 What is genuinely reflective, and should be said so

- `Validator.forExecutables().validateParameters(Object, Method, …)` and the constructor variants: the API
  hands over `java.lang.reflect` objects. The right move is the one `DefaultValidatorConfiguration` already
  makes for beans at line 423 — map the `Method` to the `ExecutableMethod` of the bean definition or the
  `BeanMethod` of the introspection — and reflect only when neither exists.
- `ParameterNameProvider`: as above.
- `ServiceLoader`-registered `ValueExtractor`, `ConstraintValidatorFactory`, `MessageInterpolator`,
  `TraversableResolver`, `ClockProvider`: instantiated reflectively by contract. Their generic type
  arguments are the one place `AnnotatedType` reflection is legitimate.
- XML mapping: class names are strings. Member binding is not forced to be reflective (see 2.2).
- Annotation *types*: `ConstraintDefinitionException` checks read the members of an `@interface`. At
  runtime that is reflection; at compilation time it is a `ClassElement` (see 3.3).

## 2. Rarely used machinery stays where the TCK needs it

### 2.1 What is right

- XML is its own module, parses with JDK APIs only, through `SecureXmlDocumentBuilder` (DOCTYPE, external
  entities, XInclude rejected; tests cover traversal in mapping paths).
- The core module has no XML, EL or `ValidationProvider` service entry; verified by grep.
- JavaFX extractors live only in `validation-jakarta`, `compileOnly`, with `@Requires(classes = ObservableValue.class)`.
- The JNDI `TckInitialContextFactory` lives in the TCK harness, nowhere else.
- The bootstrap context is narrowed to the validation packages, events, eager beans and package deduction off.

### 2.2 What to change

- `MicronautValidatorConfiguration` wires the optional modules by class name:
  `Class.forName("…reflection.ReflectionValidator")` (line 500), `…xml.XmlValidationMetadataProvider` (561),
  `…el.ElMessageInterpolator` (574), `…reflection.ReflectionConstraintValidatorFactory` (587), and
  `TckDeployableContainer` repeats three of them (335, 366, 562). `BootstrapConfigurationLoader` is already a
  `ServiceLoader` SPI that the XML module implements; extend that pattern into one
  `ValidatorBootstrapContribution` SPI (`customize(ValidatorConfigurationBuilder)`) that each optional module
  registers, and the string wiring disappears from both places.
- `XmlValidationMetadataProvider` resolves fields and getters with `getDeclaredField` /
  `getDeclaredMethods` (lines 429-506, 1533). When the target type has a `BeanIntrospection` — which every
  TCK class does — the property, its type and its type arguments are already there; reflection should be the
  branch for a type without one.
- `JavaFxValueExtractors` fail five TCK tests with "Bean introspection not found for the class" when the
  reflection module is absent. The holder types in those tests are test classes; the harness visitor should be
  introspecting them. Worth checking why it does not, because it is the difference between "JavaFX needs
  reflection" and "the harness missed a class".

## 3. Rules computed at compilation time, with core's element model

The reflection module contains four rule sets that are properties of the source:

| Reflective helper | Rule (spec section) | Compile-time equivalent |
|---|---|---|
| `ReflectionMethodDeclarations.validateParameterDeclarations` / `validateReturnValueDeclarations` / `hierarchy` / `hasCascadedReturnValueInHierarchy` | §5.6.5: parameter constraints must not be added in a subtype or in parallel interfaces; return value cascading must not conflict | `ElementQuery.ALL_METHODS.includeOverriddenMethods()`, `MethodElement.overrides(MethodElement)`, `ClassElement.getInterfaces()`; `ValidationVisitor.inheritAnnotationsForMethod` already walks the parent to copy annotations and is the place to detect the conflict |
| `ReflectionGroupConversions.validateBean` / `validate*Declarations` | §5.4.5: `@ConvertGroup` only with `@Valid`, not on the `Default` group twice, on container elements | `getTypeAnnotationMetadata()` of the type arguments, already visited by `visitTypedElementValidationAndMarkForValidationIfNeeded` |
| `ReflectionConstraintDefinitions.validate` (message/groups/payload/validationAppliesTo members, `valid*` prefix, cross-parameter validator count) | §3.1.1.x: the shape of a constraint annotation | Visit the `@interface` element (a `TypeElementVisitor` receives annotation types annotated `@Constraint`); members are `MethodElement`s with `getReturnType()`; defaults through `getDefaultValues` |
| `ReflectionGroupSequences.validationGroupPasses` / `hasInheritedDefaultGroupSequence` / `defaultGroupPasses` | §5.4.3, §5.4.4: default group sequence, redefinition, inheritance | `DefaultConstraintValidatorContext.findGroupSequences` in the core validator already computes this from `AnnotationMetadata` and caches it per introspection (`DefaultValidator.findGroupSequencesCache`). The reflective copy exists only because `ReflectionValidator` does not feed metadata in |

The one complication is **when** the TCK wants the failure. `ConstraintDeclarationException` and
`ConstraintDefinitionException` are expected at validation time, from a class that compiled. A processor
that failed the build would fail the TCK. So the processor records the verdict rather than enforcing it:
`element.annotate("io.micronaut.validation.annotation.ConstraintDeclarationError", b -> b.value(message))`
on the offending method, parameter or constraint type, and `DefaultValidator` throws the specification's
exception when it reaches an element carrying it. That keeps the rule in one place, written against the AST,
and leaves the runtime with a single string check. `ValidationVisitor` is the natural host; it already has
the hierarchy walk and runs at order 10.

Where core falls short: nothing blocks this. Two additions would make it cleaner and are small —
`ElementQuery.ALL_METHODS.onlyOverriding()` style filters exist; a `MethodElement.getOverriddenMethods()`
returning the full chain across interfaces would replace the hand-written `hierarchy()` walks in three
helpers. That is the kind of change the principle allows for.

The 119 `metadata` failures are a different gap of the same kind: `IntrospectedBeanDescriptor` builds
property descriptors only. `BeanIntrospection` exposes `getConstructorArguments()` and `getBeanMethods()`
with full argument metadata, and `TestClassVisitor` (TCK harness, line 88) can add `@Executable` to the
methods it introspects. Constructor, method, parameter, return value and cross parameter descriptors then
come from generated metadata, and `ReflectionValidator`'s nine descriptor types are not needed.

## 4. One reflection-to-metadata bridge, shared with micronaut-jaxrs

Today the conversion from `java.lang.reflect` to Micronaut metadata is written six times:

| Copy | Builds | Semantics it gets wrong or differently |
|---|---|---|
| `ReflectionValidationMetadataProvider.addConstraintAnnotation` + `annotationValues` + `annotationDefaultValues` | `MutableAnnotationMetadata` with `@Constraint` stereotype | Adds only the `Constraint` stereotype, by hand; does not run `ValidationAnnotationRemapper`, so the `$validatedBy` member and the `@Inherited` stereotype the generated metadata has are missing |
| `ReflectionValidator.annotationValues` (line 3315) | Attribute maps | Separate copy of the same loop |
| `DefaultConstraintDescriptor.annotationValues` / `defaultValues` | Attribute maps for composing constraints | Third copy, in the core module |
| `XmlValidationMetadataProvider` (lines 260-660) | `MutableAnnotationMetadata` from XML elements | Own stereotype handling |
| `DefaultValidatorConfiguration.annotationMetadataOf` / `argumentOf` | `Argument` from `AnnotatedType` | Pre-existing; no stereotypes, no repeatables |
| micronaut-jaxrs #641: `JaxRsReflectionClientComponentInstantiator.annotationMetadata`, `JaxRsRequestFieldInjectionInterceptor` (`Argument.of(field.getGenericType())` + `MutableAnnotationMetadata`) | `AnnotationMetadata`, `Argument` | No stereotypes at all, so `hasStereotype` is false for everything |

Each copy answers `hasStereotype`, repeatable containers, defaults and inherited annotations slightly
differently, which is exactly the class of bug the element-model probe in `ConstraintMessageELVisitor`
hit at compile time — and at runtime there is no diagnostic to catch it.

The shared bridge belongs in micronaut-core, next to `AnnotationReflectionUtils` (which already does the
`AnnotatedType`-to-`Argument` half for a supertype), and both PRs depend on it:

1. `ReflectionAnnotationMetadataBuilder` — `AnnotationMetadata` from an `AnnotatedElement`: declared
   annotations, meta-annotations as stereotypes (recursively, the way `AbstractAnnotationMetadataBuilder`
   does it at compile time), `@Repeatable` containers expanded, `Method.getDefaultValue()` registered as
   defaults, `@Inherited` honoured for classes, and the `AnnotationMapper` / `AnnotationTransformer` /
   `AnnotationRemapper` services applied so that `ValidationAnnotationRemapper` runs at runtime too. Built on
   `MutableAnnotationMetadata` and `AnnotationMetadataSupport`, which are in `micronaut-inject`; the
   compile-time builder is in `micronaut-core-processor` and cannot be reused directly, but its stereotype
   resolution is the reference behaviour.
2. `Argument.of(AnnotatedType)` / `Argument.of(Parameter)` / `Argument.of(Field)` — type, type parameters
   recursively, and the annotation metadata of each level from (1), so container element constraints on
   `List<@Size String>` arrive as type-use metadata on the type argument, the same shape the processor emits.
3. `ReflectionExecutableMethod` — `ExecutableMethod` over a `Method`, arguments from (2), invocation through
   `ReflectionUtils.invokeMethod`. `AbstractExecutableMethod` in `micronaut-inject` is the base.
4. `ReflectionBeanIntrospection` — `BeanIntrospection` over a `Class`: properties from fields and accessors,
   constructor arguments, `BeanMethod`s from (3). Registered through a `BeanIntrospector` that falls back to
   it when no generated introspection exists.

With (4) in place `DefaultValidator` does not change: a non-introspected type gets a reflective
introspection whose metadata looks exactly like a generated one, `findGroupSequences`, the descriptors, the
paths and the violations are the existing ones, and the reflection module is the provider plus the
once-per-type warning the plan asks for. micronaut-jaxrs #641's `jaxrs-reflection` module (1 176 lines,
same shape: `JaxRsRequestBeanAnnotationBinder`, `JaxRsRequestFieldInjectionInterceptor`,
`JaxRsReflectionParamConverterProvider`, `JaxRsReflectionClientComponentInstantiator`) drops to the same
thing.

## 5. The compile-time EL additions against the same principles

- `validation-el`: evaluation goes through `CompiledExpressionFactory` and `CompiledELContext`, so property
  and method resolution is the introspection dispatch table; `LocaleFormatter` is introspected and
  `format` executable after the variable arity fix in `IntrospectionELResolver`. No reflection on the
  compiled path; the interpreter is the documented fallback for runtime strings. Principle 1 holds.
- `validation-el-processor`: compiles the expressions of every constraint message it can see, including
  composing constraints with `@OverridesAttribute` applied and container element constraints — the same
  compile-time walk section 1.2 asks the core module to rely on. The two should converge on one generated
  representation of the composed constraint rather than each rebuilding it.
- Open: `io.micronaut.el:*` is unpublished (composite build only), identical expressions generate one class
  per declaring type, and a parameter nested in an expression is not substituted by the shared scan.

## Progress

Step 1 of the order below is implemented on the `claude/reflection-bridge` branches of micronaut-core and of
this repository; [reflection-bridge.md](reflection-bridge.md) describes it and measures it.

## Recommended order

1. **micronaut-core: the reflection bridge** (section 4). It unblocks both PRs and is the only piece that
   cannot be done inside this repository.
2. **`validation-processor`: record declaration and definition verdicts; `IntrospectedBeanDescriptor`:
   executable descriptors from the introspection** (section 3). Removes roughly 270 of the 405 tests from
   the reflection column with no reflection added.
3. **Replace `ReflectionValidator` with the reflective introspection fed into `DefaultValidator`**
   (section 1.1). Deletes the duplicated descriptors, paths, nodes, violations and rules.
4. **Core module: composing constraints from stereotypes, `@OverridesAttribute` as a transformer, validator
   target types from `BeanDefinition` / `AnnotationReflectionUtils`** (section 1.2). Returns the lean module
   to reflection-free.
5. **Bootstrap: SPI instead of `Class.forName` wiring; XML: introspection-first member binding** (section 2.2).

Keep as is: the module topology, the secure XML parsing, the narrowed bootstrap context, the JavaFX and JNDI
placement, the TCK evidence workflow, and the `ValidationMetadataProvider` SPI — which is the right seam, and
becomes more useful once every provider hands the validator metadata instead of descriptors.
