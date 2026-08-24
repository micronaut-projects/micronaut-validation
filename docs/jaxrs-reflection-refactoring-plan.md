# micronaut-jaxrs: refactoring the reflective code onto the core bridge

A work plan for [micronaut-jaxrs#641](https://github.com/micronaut-projects/micronaut-jaxrs/pull/641),
written the way the [`core-issues/`](core-issues/README.md) items are: what is wrong, the evidence, what to
do, the tests, and the size. The background — what the bridge is and why micronaut-validation stopped
maintaining a reflective implementation — is in
[`reflection-bridge-for-jaxrs.md`](reflection-bridge-for-jaxrs.md).

Measured against `pr-641` at `f7a401b8`.

## The principle

Not "replace reflection with something else". Reflection is the right tool for a type the processor never
saw. The principle is **one description and one translation**: `java.lang.reflect` → Micronaut metadata
happens once, in the core bridge, and each JAX-RS annotation is translated into Micronaut binding metadata
once, wherever that metadata is built.

Today `jaxrs-reflection` is 1 202 lines over 8 classes, of which roughly 700 re-implement one of those two
things.

| Work item | What it fixes | Tests | Size |
| --- | --- | --- | --- |
| [W1 — one translation of the JAX-RS binding annotations](#w1) | two implementations that already disagree | parity, 7 annotations | medium |
| [W2 — the field walk becomes property members](#w2) | ~150 lines of hand-rolled reflection | existing + non-public fields | medium |
| [W3 — the request-bean binder supplements](#w3) | `@BeanParam` on a type without `@Introspected` throws | 1 | small |
| [W4 — one instantiation path](#w4) | three copies of constructor reflection | existing | small |
| [W5 — client component injection](#w5) | field and method injection by hand | existing | small–medium |
| [W6 — two switches and three TCK profiles](#w6) | nothing measures what each half contributes | the TCK itself | small |
| [W7 — the TCK harness](#w7) | JAX-RS classes in `io.micronaut.validation.tck` | existing | small |
| [W8 — what micronaut-validation should hand to core](#w8) | helpers both projects need | core specs | medium |

W1 first. W2, W3 and W6 depend on it; the rest are independent. W8 is a micronaut-core change that W2, W4
and W5 would consume rather than reimplement — worth reading before starting them.

---

<a id="w1"></a>
## W1 — One translation of the JAX-RS binding annotations

### The evidence

The translation from JAX-RS annotations to Micronaut binding annotations exists **twice**, and the two
versions do not agree.

At compile time, `jaxrs-processor` maps them with `NamedAnnotationMapper`s:

```java
// HeaderParamMapper
final AnnotationValueBuilder<Header> builder = AnnotationValue.builder(Header.class);
annotation.stringValue().ifPresent(builder::value);

// PathParamMapper
final AnnotationValueBuilder<PathVariable> builder = AnnotationValue.builder(PathVariable.class);
… pathParamBuilder.stereotype(AnnotationValue.builder(Bindable.class).build()).build(),
```

At runtime, `JaxRsRequestFieldInjectionInterceptor.fieldArgument` builds the same metadata by hand, with a
seven-branch `if`/`else` and a different result:

```java
} else if (headerParam != null) {
    String value = annotationValue(headerParam);
    addRequestParam(annotationMetadata, HeaderParam.class, value);   // @HeaderParam(value)
    addBindable(annotationMetadata, HeaderParam.class, value, fieldDefaultValue(field));  // @Bindable(value)
}
```

So a `@HeaderParam` **method parameter** carries `@Header`, and a `@HeaderParam` **field** carries
`@HeaderParam` + `@Bindable`. A `@PathParam` parameter carries `@PathVariable`; a `@PathParam` field carries
`@Bindable`. Any binder, filter or piece of documentation that keys off `@Header` or `@PathVariable` sees
fields and parameters differently. This is worth fixing on its own merits, before any of the rest.

### What to do

1. Extract the mapping into `jaxrs-common` — which `jaxrs-processor` already depends on
   (`implementation projects.micronautJaxrsCommon`) and `jaxrs-reflection` re-exports
   (`api(projects.micronautJaxrsCommon)`). A pure function per annotation:
   `AnnotationValue<?> → List<AnnotationValue<?>>`, no `VisitorContext` — `HeaderParamMapper` already ignores
   it, and the others use it only for warnings.
2. Have every `*Mapper` in `jaxrs-processor` delegate to it, so compile-time behaviour is unchanged by
   construction.
3. Add a `ReflectionAnnotationCustomizer` in `jaxrs-reflection` that delegates to the same functions. It is
   registered in `META-INF/services/io.micronaut.inject.annotation.ReflectionAnnotationCustomizer` and the
   core builder applies it to every annotation it reads, wherever it reads it — so the field path, the
   `@BeanParam` path and anything added later all get the identical translation for free.

The customizer is the extension point the bridge exists for; micronaut-validation uses one to copy
`@Constraint.validatedBy` into the `$validatedBy` member the processor writes.

### Tests

A parity test is the one that matters, and it is cheap: for each of `@MatrixParam`, `@QueryParam`,
`@HeaderParam`, `@CookieParam`, `@PathParam`, `@FormParam`, `@BeanParam` (plus `@DefaultValue` and
`@Encoded`), assert that the metadata the processor produces for a method parameter and the metadata the
bridge produces for a field of the same declaration are equal. Any future divergence then fails a test rather
than a TCK run.

---

<a id="w2"></a>
## W2 — The field walk becomes property members

### The evidence

`JaxRsRequestFieldInjectionInterceptor` is 259 lines, of which `findRequestFields`, `fieldArgument`,
`isInjectableRequestField`, `fieldDefaultValue`, `hasAnnotation` ×2, `findAnnotation` ×2 and
`annotationValue` — roughly 150 — are a reimplementation of what the bridge does:

```java
while (current != null && current != Object.class) {
    for (Field field : current.getDeclaredFields()) {
        if (isInjectableRequestField(field)) {
            field.setAccessible(true);
            …
```

### What to do

Replace the walk with `ReflectionBeanIntrospection.of(resourceClass)` and
`ReflectiveIntrospection.getPropertyMembers(name)`, which returns one `PropertyMember` per field, getter and
setter across the hierarchy, each carrying its `ElementType`, its declaring type, its annotation metadata
(already translated by W1) and its `Argument`. Keep the `Field` handle for writing — `PropertyMember` exposes
`read(bean)` but injection needs a write, so either use `BeanProperty.set` or the member's `Field`.

With W1 in place, `fieldArgument` disappears entirely: the argument is
`AnnotationReflectionUtils.argumentOf(field)` and the metadata is
`ReflectionAnnotationMetadataBuilder.build(field)`.

Two behaviours the bridge already handles that the current code does not: a field declared by a non-public
superclass (`trySetAccessible` on every member, fixed in core `f4903c6f`), and type-use annotations on the
field's generic parameters.

### Tests

`JaxRsRequestFieldInjectionTest` should keep passing unchanged. Add a resource whose `@QueryParam` field is
private and declared on a package-private superclass.

---

<a id="w3"></a>
## W3 — The request-bean binder supplements

### The evidence

```java
private static final ConcurrentMap<ClassLoader, BeanIntrospector> INTROSPECTORS = new ConcurrentHashMap<>();
…
BeanIntrospection<Object> beanIntrospection = classLoader == null
    ? BeanIntrospection.getIntrospection(type)
    : (BeanIntrospection<Object>) INTROSPECTORS.computeIfAbsent(classLoader, BeanIntrospector::forClassLoader).getIntrospection(type);
```

`getIntrospection` throws when the type has no generated introspection, so a `@BeanParam` class from a jar
compiled without the Micronaut processor cannot be bound at all.

### What to do

Wrap the per-loader introspector:

```java
INTROSPECTORS.computeIfAbsent(classLoader, cl ->
    new ReflectionBeanIntrospector(BeanIntrospector.forClassLoader(cl), type -> true, true));
```

Nothing else in the class changes. Everything it uses afterwards — `getBeanProperties()`,
`getConstructorArguments()`, `instantiate(false, args)`, `BeanProperty.set` — is `BeanIntrospection` API that
the reflective and supplemented introspections implement. Generated introspections keep winning where they
exist.

One known limit to note in the code: a `@BeanParam` type with more than one constructor is described by its
creator constructor alone — that is [core issue 01](core-issues/01-bean-introspection-constructors.md),
which the reflective side already solves via `ReflectiveIntrospection.getConstructors()`.

### Tests

Bind a `@BeanParam` parameter whose type carries no `@Introspected`, with both a constructor-based and a
setter-based variant.

---

<a id="w4"></a>
## W4 — One instantiation path

### The evidence

The same "declared no-arg constructor, make it accessible, instantiate, unwrap `InvocationTargetException`"
appears three times: `JaxRsReflectionProviderInstantiator` (essentially the whole class, 62 lines), the
`Application` instantiation in `JaxRsReflectionSeBootstrapProvider`, and the constructor half of
`JaxRsReflectionClientComponentInstantiator`.

### What to do

`introspector.findIntrospection(type).map(BeanIntrospection::instantiate)` — `ReflectionBeanIntrospection`
already selects the constructor, calls `trySetAccessible` and instantiates.

Keep the exception handling at the call site: the bridge throws
`io.micronaut.core.reflect.exception.InstantiationException` with the target exception as its cause, and JAX-RS
wants the cause propagated (`RuntimeException` rethrown, `Error` rethrown, anything else wrapped). That logic
is worth keeping in one place too — a small static helper next to the shared introspector.

### Tests

The existing `JaxRsReflectionClientComponentInstantiatorTest` and
`JaxRsReflectionSeBootstrapProviderTest` cover this; add one provider whose constructor throws, asserting the
cause survives.

---

<a id="w5"></a>
## W5 — Client component injection through property members

`JaxRsReflectionClientComponentInstantiator` (173 lines) reflects over `annotationType.getMethods()`, then
over fields and `getDeclaredMethods()` with `setAccessible`. The field half is W2's `getPropertyMembers`; the
method half is `ReflectiveIntrospection.findDeclaredMethod(name, parameterTypes)` or the introspection's bean
methods, both of which hand back an `ExecutableMethod` with proper `Argument`s instead of a raw `Method`.

Do this after W2 — it is the same mechanism, applied to a second call site.

---

<a id="w6"></a>
## W6 — Two switches and three TCK profiles

### Why

While the processor and the reflective description both run, neither is measured. micronaut-validation split
its TCK three ways and it immediately paid for itself: the narrow profiles exposed three real defects that
the combined path had masked, and produced the exact list of what the processor does not generate.

### What to do

Add the two switches:

- `micronaut.jaxrs.reflection.enabled` (default `true`) — where the module installs its supplementing
  introspector, so a native image can prove what it can do without it.
- `micronaut.jaxrs.tck.processor.enabled` (default `true`) — in the copied `ArchiveCompiler`,
  `task.setProcessors(List.of())` plus `-proc:none` on the compiler options, so the TCK archive is compiled
  with no Micronaut processor at all.

Then three Gradle tasks over the same suite: both on, processor only, reflection only. Register the system
properties on the `Test` task, **not** inside `useTestNG { systemProperties = […] }` — that assignment
replaces the task's whole map and silently drops them, which cost an hour here.

Generate the exclusion list from a run with no exclusions rather than writing it by hand, and put the cause
in a comment next to each entry.

### What to expect

From the validation experience:

- *Reflection only* should fail only where a **Micronaut bean definition** is required — interception and
  injection into container-managed components. In validation that was 14 of 1054. Anything else is a bridge
  gap worth reporting to core.
- *Processor only* produces the list of what the JAX-RS processor should generate — the analogue of the six
  filed core issues.

---

<a id="w7"></a>
## W7 — The TCK harness

`tests/jaxrs-tck` contains a copy of micronaut-validation's Arquillian harness — `ArchiveCompiler`,
`ArchiveCompilerException`, `ArchiveCompilationException`, `DeploymentClassLoader`, `DeploymentDir`,
`TckDeployableContainer`, `TckProtocol`, `TckExtension`, `TckContainerConfiguration`, `ArchiveCompilerTest` —
**in package `io.micronaut.validation.tck`**.

Immediately: rename the package. Shipping JAX-RS test classes under `io.micronaut.validation` is wrong
regardless of what happens next.

Then port the two fixes made here: `ArchiveCompilationException` and `ArchiveCompilerException` were merged
into one (they were the same exception with two names), and the archive classpath is now passed to every TCK
task rather than only the compliance one, so a focused run compiles the same archives.

Longer term the harness is the obvious second extraction — compile an Arquillian archive with the Micronaut
processor, load it in a child-first loader, run a TCK against it, is specific to neither specification. One
shared `micronaut-tck-harness` test artifact would serve both repositories.

---

<a id="w8"></a>
## W8 — What micronaut-validation should hand to core

While replacing the reflective validator, micronaut-validation grew a handful of helpers around the bridge.
Most are Jakarta Validation and belong where they are — `IntrospectedBeanDescriptor` and
`IntrospectedExecutableDescriptors` implement `jakarta.validation.metadata.*`, `ConstraintContainers` and
`ConstraintDefinitions` are constraint rules. But four are specification-neutral (zero `jakarta.validation`
references), and JAX-RS needs the same things the moment W2–W6 land. They are better moved to core than
copied.

| Candidate | Lines | What it is | Who needs it |
| --- | ---: | --- | --- |
| `ReflectiveExecutables` | 152 | `Method` → `ExecutableMethod` and `Constructor` → `BeanConstructor`, resolved *in order*: the bean definition of the declaring type, else the introspection's `BeanMethod`, else the reflective one | every consumer of the bridge — W4 and W5 directly |
| `IntrospectedExecutableMethod` | 63 | an `ExecutableMethod` over a `BeanMethod`; the adapter the resolution above needs | as above |
| `ExecutableHierarchy` (the traversal half, ~180 of 486) | 180 | given a method, the declarations it overrides or implements across super classes and every interface, each level's *declared-only* metadata, and the merge of the levels | any specification with annotation inheritance rules — JAX-RS §3.6 among them |
| `ConfiguredMetadata.merge` | 67 | merge a list of `AnnotationMetadata` into one, last level winning | small; arguably a static on `AnnotationMetadataHierarchy` |

`ContainerTypeArguments` is **not** a candidate: it maps a value extractor's type argument onto a declared
container through the hierarchy, which is a Bean Validation concept. The generic unwrapping JAX-RS does in
`JaxRsJaxbElementMessageBodyReaderWriter` and `JaxRsXmlSseEventDataReader` (`genericType instanceof
ParameterizedType` → `getActualTypeArguments()[0]`) is one level deep and already served by
`AnnotationReflectionUtils.argumentOf(AnnotatedType)`, which additionally carries the type-use annotations
those two classes currently drop.

### Why the hierarchy one matters most

Both projects implement "what did this method inherit", and neither can use the other's implementation:

- **JAX-RS** does it at compile time — `JaxRsTypeElementVisitor` iterates `overriddenMethods` over
  `MethodElement` (lines 656 and 1085) to apply §3.6 annotation inheritance.
- **Validation** does it at runtime — `ExecutableHierarchy.resolve` walks super types through
  `BeanIntrospector` to apply §5.6.5 declaration rules.

They are the same traversal against two metadata models. A JAX-RS reflection profile (W6) needs the runtime
one, because a resource compiled without the processor has no `MethodElement` and no bean definition. Moving
the traversal to core — `resolve`, `inherited`, `declaredBy`, `mergeArgument`, `merge`, `declaredOf` and the
`Declaration`/`Resolved` records, leaving `checkGroupConversions`, `constraintNames` and the rest of the rules
in validation — gives JAX-RS §3.6 at runtime for free and removes a duplicate from the ecosystem.

### What to do

1. It is filed as [core issue 07](core-issues/07-reflective-executable-resolution.md), alongside the six
   others, with the split boundary, the tests and the one design decision it carries (the package-skip
   heuristic in `declaredBy`, which is right for Bean Validation and wrong for JAX-RS).
2. Move `ReflectiveExecutables` + `IntrospectedExecutableMethod` first — they are self-contained, have no
   validation concepts in them, and W4/W5 are blocked on the same functionality.
3. Split `ExecutableHierarchy` along the line drawn above. The validation TCK is the regression test: the
   introspection profile exercises the traversal heavily, so a mistake shows up as a number.
4. `ConfiguredMetadata.merge` last, if at all — it is small enough that duplicating it is not a real cost,
   and the right home may be `AnnotationMetadataHierarchy` rather than a new class.

Sequencing note: none of this blocks #641. W2–W5 can call the validation-side classes' equivalents inline and
be simplified once core has them — but knowing they are coming is the difference between writing them twice
and writing them once.

---

## What stays reflective, and should

- **`JaxRsReflectionParamConverterProvider`** — locating `valueOf`, `fromString` or a `(String)` constructor
  is a JAX-RS rule about types, not metadata about a bean. At most, read those members through
  `ReflectionBeanIntrospection` so accessibility is handled in one place.
- **`JaxRsReflectionTypeConverterRegistrar`** — registrations only, no reflection.
- **The SE bootstrap discovery** in `JaxRsReflectionSeBootstrapProvider` beyond its instantiation — scanning
  a supplied `Application` for resources is specification behaviour.

Unlike micronaut-validation, where the reflective module was deleted outright, expect `jaxrs-reflection` to
survive as a much smaller module: an introspector, a customizer, and the genuinely JAX-RS-specific rules.

## Sequencing and verification

```
W1 ──┬── W2 ── W5
     ├── W3
     └── W6 (needs W1..W3 to be meaningful)
W4, W7 independent
W8  in micronaut-core, consumed by W2, W4 and W5
```

After each item: the module's own tests, then `jakartaTck` unchanged. After W6, all three profiles, and the
exclusion lists regenerated rather than edited.

The measure of success is not "reflection removed". It is that a JAX-RS resource compiled without the
Micronaut processor behaves the same as one compiled with it — and that there is exactly one place where a
`@QueryParam` becomes a Micronaut binding annotation.
