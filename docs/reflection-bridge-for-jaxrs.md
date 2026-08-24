# The reflection bridge: what micronaut-validation did, and what micronaut-jaxrs #641 should do

Two specification-compliance efforts hit the same wall at the same time. Jakarta Validation and Jakarta REST
both require a provider to describe types the application never asked Micronaut to introspect — a constraint
declared on a class from a third-party jar, a JAX-RS resource compiled without the Micronaut processor — and
Micronaut's metadata is generated at compilation time.

[micronaut-validation#631](https://github.com/micronaut-projects/micronaut-validation/pull/631) answered that
with a `validation-reflection` module: a second validator, ~9 800 lines, that re-implemented the whole
specification over `java.lang.reflect`. [micronaut-jaxrs#641](https://github.com/micronaut-projects/micronaut-jaxrs/pull/641)
answered it with a `jaxrs-reflection` module: 1 202 lines of hand-rolled reflection, and the same idea.

This document describes the alternative that replaced the first one — a reflection *bridge* in micronaut-core
that produces ordinary Micronaut metadata — and what applying it to #641 would look like.

---

## Part 1 — What was done in micronaut-validation

### The idea

Do not re-implement the specification over reflection. Implement `java.lang.reflect` → Micronaut metadata
**once**, in core, so that every consumer keeps exactly one code path: the one that reads
`AnnotationMetadata`, `Argument`, `BeanIntrospection` and `ExecutableMethod`. A type without generated
metadata produces the same objects, built at runtime instead of at compilation time.

### The bridge, in micronaut-core

Branch `claude/reflection-bridge` (off `5.1.x`), 2 073 lines of main code plus a 205-line specification.

| Class | Lines | What it gives you |
|---|---:|---|
| `inject.annotation.ReflectionAnnotationMetadataBuilder` | 453 | `build(AnnotatedElement...)` → `AnnotationMetadata` identical in shape to the generated one: stereotypes flattened, repeatables filed under their container, defaults registered by name, `Class` → `AnnotationClassValue`, enums → names, nested annotations → `AnnotationValue` |
| `inject.annotation.ReflectionAnnotationCustomizer` | 57 | SPI, loaded by `SoftServiceLoader`: `supports(Class<? extends Annotation>)` + `customize(Annotation, Map<CharSequence, Object>)`. The hook for a module to rewrite annotation values as the metadata is built |
| `context.AnnotationReflectionUtils` | +120 | `argumentOf(AnnotatedType \| Parameter \| Field)`, `argumentsOf(Executable)`, `returnArgumentOf(Method)` — `Argument`s carrying type-use annotations and nested type arguments |
| `inject.reflection.ReflectionBeanIntrospection` | 838 | A complete `BeanIntrospection` over a `Class`: properties from fields, getters and setters across the hierarchy plus interface getters, every constructor, bean methods, instantiation |
| `inject.reflection.ReflectiveIntrospection` | 120 | What a reflective introspection knows and a generated one cannot: `getConstructors()`, `getPropertyMembers(name)` (a `PropertyMember` per field/getter/setter with its `ElementType`, declaring type, annotations, argument, and `read(bean)`), `findDeclaredMethod(name, types)` |
| `inject.reflection.ReflectionBeanIntrospector` | 113 | A `BeanIntrospector` wrapping another: `(delegate, Predicate<Class<?>>, boolean supplementing)` |
| `inject.reflection.SupplementedBeanIntrospection` | 382 | Generated metadata first, reflective metadata filling only what is empty |
| `inject.reflection.ReflectionExecutableMethod` | 86 | An `ExecutableMethod` over a `Method`, metadata read lazily |
| `inject.annotation.AnnotationMetadataSupport` | +9 | `getAnnotationType(name, classLoader)` — the shared name → `Class` registry now answers with the class of the *asking* loader |

### Five decisions worth copying

1. **Supplement, never replace.** `ReflectionBeanIntrospector(delegate, type -> true, true)` returns the
   generated introspection when there is one, a `SupplementedBeanIntrospection` when both exist, and a purely
   reflective one otherwise. Generated metadata always wins; reflection only fills gaps. Applications that
   compile with the processor are unaffected.
2. **One switch.** `micronaut.validation.reflection.enabled=false` turns the supplement off, so a native image
   can prove it validates with generated metadata alone. Everything downstream is unchanged — it is the same
   validator reading the same interfaces.
3. **Domain translation belongs in a customizer, not in the builder.** Bean Validation needs
   `@Constraint.validatedBy` copied into the internal `$validatedBy` member the processor writes. That is a
   20-line `ReflectionAnnotationCustomizer` in the validation module, not a special case in core.
4. **Class loaders are part of the contract.** A TCK deploys the same annotation type in many child-first
   loaders. The metadata registry had to learn to answer with the caller's copy, and descriptors had to load
   constraint types through the context class loader.
5. **Reachability over visibility.** A reflective member of a non-public type is still the declaration that
   matters: fields, accessors and annotation members all get `trySetAccessible()`.

### What it replaced

The `validation-reflection` module — `ReflectionValidator` (5 868 lines) and eleven supporting classes — was
deleted: **9 852 deletions**. What replaced each part:

| Was | Is |
|---|---|
| `ReflectionValidator` executable paths | `DefaultValidator` + a 90-line `ReflectiveExecutables`: a `Method` resolves to the bean definition, else the `BeanMethod`, else a `ReflectionExecutableMethod` |
| `ReflectionMethodDeclarations`, `ReflectionGroupConversions` | `ExecutableHierarchy`: the declarations a method overrides are read from the introspections of its super types, merged, and checked for the §5.6.5 rules |
| `ReflectionConstraintDefinitions` | `ConstraintDefinitions` in the validation module, behind `ValidatorConfiguration.isStrictConstraintDefinitions()` |
| `ReflectionBeanMetadata` + nine descriptor records | `IntrospectedBeanDescriptor` describing properties from their `PropertyMember`s |
| `ReflectionConstraintValidatorFactory` | `DefaultInternalConstraintValidatorFactory` over the supplementing introspector |

### How it is proven

The Jakarta Validation 3.1.1 TCK runs three ways, so that what the processor contributes and what the bridge
contributes can be told apart:

| Task | Processor | Reflection | Runs | Excluded |
|---|:--:|:--:|---:|---:|
| `jakartaTck` | ✅ | ✅ | **1054** | **0** |
| `jakartaTckIntrospection` | ✅ | ❌ | 961 | 93 |
| `jakartaTckReflection` | ❌ | ✅ | 1019 | 14 |

The third row is the interesting one for #641: **with no Micronaut processor at all, the reflection bridge
alone passes everything except 14 CDI tests**, and those 14 fail because they expect the *container* to have
intercepted a method call — a compile-time proxy in Micronaut — not because of validation metadata.

### Three defects the narrow profiles exposed

Running the profiles separately found real bugs the combined path had masked, which is an argument for
building the same two switches into #641:

- `DefaultInternalConstraintValidatorFactory` dereferenced a null bean context instead of calling the
  validator's no-arg constructor, which the specification requires of the default factory.
- A property whose value could not be read reported the property name and swallowed the cause.
- `ReflectionBeanIntrospection` never called `trySetAccessible()` on accessors, so a public getter on a
  non-public class threw `IllegalAccessException`.

---

## Part 2 — What should be done in micronaut-jaxrs #641

### What the module does today

`jaxrs-reflection`, 1 202 lines over 8 classes, wired as an optional module the TCK depends on.

| Class | Lines | Reflection it performs |
|---|---:|---|
| `JaxRsReflectionSeBootstrapProvider` | 269 | `getDeclaredConstructor()` + `setAccessible` + `newInstance` on the `Application`; `getDeclaredMethods()` over resources |
| `JaxRsRequestFieldInjectionInterceptor` | 259 | walks `getDeclaredFields()` up the hierarchy with `setAccessible`, hand-builds a `MutableAnnotationMetadata` per field from the JAX-RS annotations, and `Argument.of(field.getGenericType())` |
| `JaxRsRequestBeanAnnotationBinder` | 178 | `ConcurrentMap<ClassLoader, BeanIntrospector>` → `getIntrospection(type)`, which throws when the type has no generated introspection |
| `JaxRsReflectionClientComponentInstantiator` | 173 | annotation `getMethods()`, constructor/field/method reflection with `setAccessible` |
| `JaxRsReflectionParamConverterProvider` | 134 | `getConstructor(String)` and `getMethod(name, String.class)` factory lookups |
| `JaxRsReflectionTypeConverterRegistrar` | 103 | registrations only — no reflection |
| `JaxRsReflectionProviderInstantiator` | 62 | `getDeclaredConstructor()`, `canAccess`, `setAccessible`, `newInstance`, exception unwrapping |

### The mapping

| Today | Replace with |
|---|---|
| `JaxRsRequestFieldInjectionInterceptor.findRequestFields` — the field walk | `ReflectionBeanIntrospection.of(type).getPropertyMembers(name)`, which already returns each field/getter/setter with its `ElementType`, declaring type and a `read(bean)` |
| `JaxRsRequestFieldInjectionInterceptor.fieldArgument` — the hand-built metadata | `AnnotationReflectionUtils.argumentOf(field)` for the `Argument` and `ReflectionAnnotationMetadataBuilder.build(field)` for the metadata |
| `addRequestParam` / `addBindable` — translating `@QueryParam`, `@HeaderParam`, `@PathParam`, `@DefaultValue`, `@Encoded` into `@Bindable` and Micronaut binding annotations | **a `ReflectionAnnotationCustomizer`.** This is the piece the bridge was designed for: the translation stays in jaxrs, runs wherever metadata is built, and the interceptor stops being a metadata builder |
| `JaxRsRequestBeanAnnotationBinder.INTROSPECTORS` | `ReflectionBeanIntrospector(BeanIntrospector.forClassLoader(cl), type -> true, true)` — same per-loader cache, but a type without a generated introspection is described instead of throwing |
| `JaxRsReflectionProviderInstantiator`, the `Application` instantiation in `JaxRsReflectionSeBootstrapProvider`, the constructor half of `JaxRsReflectionClientComponentInstantiator` | `introspector.findIntrospection(type).map(BeanIntrospection::instantiate)` — including the `setAccessible` and the `InvocationTargetException` unwrapping |
| `JaxRsReflectionClientComponentInstantiator` field and method injection | `getPropertyMembers` for fields; `ReflectionExecutableMethod` or `findDeclaredMethod` for methods |
| `JaxRsReflectionParamConverterProvider` | Keep. Looking up `valueOf`/`fromString`/`(String)` factories is a JAX-RS rule, not metadata; at most read them through `ReflectionBeanIntrospection` for the `setAccessible` handling |

### A plan

1. **Depend on the bridge.** Build against the `claude/reflection-bridge` branch of micronaut-core
   (a copy of its main sources, as micronaut-validation carries under
   `validation/src/main/java/io/micronaut/inject/`, or an `includeBuild` of a sibling checkout) until it is
   released.
2. **Write the customizer first.** Move the JAX-RS → Micronaut annotation translation out of
   `JaxRsRequestFieldInjectionInterceptor` into a `ReflectionAnnotationCustomizer`, registered in
   `META-INF/services`. Verify it standalone: build metadata for a field annotated `@QueryParam("q")
   @DefaultValue("1")` and assert it carries `@Bindable`. This is the highest-risk piece and the one that
   pays for itself everywhere else.
3. **Replace the field walk and the binder lookup.** With the customizer in place, the interceptor becomes a
   loop over `getPropertyMembers`, and the binder's map becomes a supplementing introspector. Expect both
   classes to lose more than half their lines.
4. **Replace the four instantiation sites** with `findIntrospection(...).instantiate()`.
5. **Add the two switches** and split the TCK task in three, exactly as above: processor-only, reflection-only,
   and both. The reflection-only run is the one that tells you whether `jaxrs-reflection` still has a reason
   to exist; the processor-only run tells you what the JAX-RS processor should be generating.
6. **Then decide the module's fate.** In validation the answer was deletion. In JAX-RS a residue will remain —
   `JaxRsReflectionParamConverterProvider` and the SE bootstrap discovery are JAX-RS rules, not metadata — so
   expect a much smaller module rather than none.

### A second, unrelated duplication

`tests/jaxrs-tck` contains a copy of micronaut-validation's Arquillian harness — `ArchiveCompiler`,
`ArchiveCompilerException`, `ArchiveCompilationException`, `DeploymentClassLoader`, `DeploymentDir`,
`TckDeployableContainer`, `TckProtocol`, `TckExtension`, `TckContainerConfiguration`, `ArchiveCompilerTest` —
**still in package `io.micronaut.validation.tck`**. Two notes:

- Shipping JAX-RS classes in a `io.micronaut.validation` package should be fixed regardless.
- The harness — compile an Arquillian archive with the Micronaut processor, load it in a child-first class
  loader, run the TCK against it — is not specific to either specification. It is the obvious second
  extraction: one `micronaut-tck-harness` test artifact for both repositories.

Two fixes made here are worth porting even if nothing else is: `ArchiveCompilationException` and
`ArchiveCompilerException` were merged into one, and the archive classpath is passed to every TCK task rather
than only the compliance one.

### How to verify

The recipe that worked here, in order of cost:

1. `jakartaTck`-equivalent green with both switches on — no regression.
2. Reflection-only run: everything that fails should be about *bean definitions and interception*, not about
   metadata. Anything else is a bridge gap worth reporting back to core.
3. Processor-only run: what fails is the list of things the JAX-RS annotation processor does not generate yet
   — the same kind of list validation ended with (constructors beyond the creator one, super types and
   interfaces, per-member property metadata).
4. Measure exclusions, never assume them: run the full suite with none, and generate the exclusion file from
   the failures with a comment naming the cause.

### What the bridge does not do yet

The gaps are filed one issue per problem in [`core-issues/`](core-issues/README.md), each with the evidence,
what to implement and the tests to add. They were measured on the validation TCK, but every one of them is a
limit of the *generated* metadata, so the same gaps will shape what a JAX-RS processor-only profile can do:

| Issue | Validation tests it blocks |
|---|---:|
| [01 — a bean introspection describes only one constructor](core-issues/01-bean-introspection-constructors.md) | 41 |
| [02 — no declared-only view of `AnnotationMetadata`](core-issues/02-declared-only-annotation-metadata.md) | 19 |
| [03 — a bean property merges a field and its getter](core-issues/03-per-member-bean-properties.md) | 7 |
| [04 — an imported type is introspected twice](core-issues/04-duplicate-introspection-for-imported-types.md) | 20 |
| [05 — validation advice matches every method](core-issues/05-validation-advice-matches-every-method.md) | 4, and a live defect |
| [06 — constructors are not intercepted](core-issues/06-constructor-interception.md) | 2 |

Issue 01 is the one most likely to matter to JAX-RS as well: a resource or provider with more than one
constructor is described by its creator constructor alone.

The 14 methods `jakartaTckReflection` excludes are deliberately **not** filed. Every one needs a Micronaut
bean definition — compile-time interception, or injection into a component the container never built — and
closing them would mean runtime bean definitions and runtime proxying. The recommendation there, which
applies unchanged to #641, is to document the boundary: the reflective description covers *metadata*;
interception and injection stay compile-time features.
