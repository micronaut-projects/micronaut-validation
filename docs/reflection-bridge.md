# The reflection bridge: one reflective metadata, fed to one validator

Branch `claude/reflection-bridge`, on top of `claude/el-compile-time-messages`. Pairs with the branch of the
same name in `../micronaut-core`, which this build includes as a composite when the checkout carries the
bridge (see `settings.gradle`; `-PmicronautCorePath=…` overrides the location).

This is the first step of the order recommended by [the review](spec-compliance-review.md): the shared
bridge in micronaut-core, then the validation modules consuming it instead of converting
`java.lang.reflect` to Micronaut metadata by hand.

## What micronaut-core gained

All in `micronaut-inject`, so that both this repository and micronaut-jaxrs can depend on it; ODI's
`AnnotationReflection.toAnnotationValue` is the same conversion and can be retired for it.

| Type | What it is |
|---|---|
| `io.micronaut.inject.annotation.ReflectionAnnotationMetadataBuilder` | `AnnotationMetadata` from an `AnnotatedElement`, shaped like the generated one: meta-annotations as stereotypes recursively, repeatable annotations filed under their container (and the container registered so `getAnnotationValuesByType` finds them), defaults registered and reachable through `getDefaultValues`, class values as `AnnotationClassValue`, enum values as names, nested annotations as `AnnotationValue`, inherited class annotations present but not declared, members of non-public annotation types read. An `AnnotationValueProvider` instance returns its own value |
| `io.micronaut.inject.annotation.ReflectionAnnotationCustomizer` | A service SPI, the runtime counterpart of the annotation mappers and remappers of the processors, which cannot run at runtime. Receives the values of every annotation the builder converts |
| `io.micronaut.context.AnnotationReflectionUtils` | Its private `AnnotatedType` conversion is now public as `argumentOf(AnnotatedType \| Parameter \| Field)`, `argumentsOf(Executable)` and `returnArgumentOf(Method)`, carrying the type-use annotations of every level — `List<@Size String>` arrives as metadata on the type argument, as the processor emits it — and its metadata comes from the builder |
| `io.micronaut.inject.reflection.ReflectionExecutableMethod` | An `ExecutableMethod` over a `Method` |
| `io.micronaut.inject.reflection.ReflectionBeanIntrospection` | A `BeanIntrospection` over a `Class`: properties merged from fields, getters and setters, the constructor selected as the processor selects it (`@Creator`, else the only public one, else the public no-arg one), the public methods as bean methods, a builder over the constructor |
| `io.micronaut.inject.reflection.ReflectionBeanIntrospector` | Serves the generated introspections of a delegate first and reflects only for the others. `BeanIntrospector.SHARED` is unchanged: reflecting is a decision the caller takes |

Three contracts the builder matches, each of which my first expectations got wrong and the generated
metadata settled: an `AnnotationValue` accessor never falls back to the defaults, which live in
`getDefaultValues()`; the constraints composing a custom constraint are flattened next to the declared ones
in `getAnnotationValuesByType`; and a stereotype lists the annotation that carries it directly, not the root
annotation of the element (`CollectionUtils.last(parentAnnotations)` in `MutableAnnotationMetadata`).

Verified by `ReflectionBridgeSpec` (9 cases) and the existing `AnnotationReflectionUtilsSpec`; `checkstyle`
clean on `inject`.

## What this repository does with it

| Before | After |
|---|---|
| `ReflectionValidationMetadataProvider` converted constraint annotations to metadata itself (own repeatable handling, a hand-added `Constraint` stereotype, no defaults) | Adds the constraint instances through `ReflectionAnnotationMetadataBuilder.add`; the container, the stereotypes and the defaults come out as generated |
| `DefaultValidatorConfiguration.argumentOf` / `annotationMetadataOf` converted the `AnnotatedType` of a value extractor itself | `AnnotationReflectionUtils.argumentOf`, `ReflectionAnnotationMetadataBuilder.build` |
| `ConstraintValidatorTargetResolver` walked `getGenericInterfaces()` to find the validated type | `AnnotationReflectionUtils.resolveGenericToArgument(type, ConstraintValidator.class).getTypeParameters()[1]` |
| `ValidationAnnotationRemapper` copied `@Constraint.validatedBy` into `$validatedBy` at compilation time only; reflective metadata had no validators | `ConstraintAnnotationCustomizer` in `validation-reflection` does the same at runtime, registered as a `ReflectionAnnotationCustomizer` |
| `validateConstructorParameters(Constructor, …)` used the one constructor the introspection knows, whatever constructor the caller named | Uses the introspection's arguments when they are the named constructor's, else `argumentsOf(constructor)` |
| `validateParameters(Object, Method, …)` / `validateReturnValue` returned an empty set when the declaring type is not a bean | Resolve through the bean definition, then the bean methods of the introspection (`IntrospectedExecutableMethod`), then `ReflectionExecutableMethod` — the API is defined on `Method`, so this last step is the reflection it imposes |

## The reflection mode switch

`-Dmicronaut.validator.reflection.mode=validator|introspection`, read by `MicronautValidatorConfiguration`
(bootstrap) and by the TCK harness, and forwarded to the TCK JVMs by `tests/jakarta-validation-tck/build.gradle`:

- `validator` (default): what #631 does — `ReflectionValidator` replaces `DefaultValidator` and runs both.
- `introspection`: `DefaultValidator` alone, with `ReflectionBeanIntrospector` over the configured
  introspector. No second validator, no duplicated descriptors, paths or rules.

## Measurements

Jakarta Validation 3.1.1 TCK, full Jakarta profile, 1054 tests:

| Configuration | Failures |
|---|---:|
| `validator` mode, before this branch | 0 |
| `validator` mode, this branch | 0 |
| no reflection module at all | 405 |
| `introspection` mode, reflective introspection only | 325 |
| + `$validatedBy` customizer, constructor arguments from the named constructor | 288 |
| + `Method` resolution through the introspection and the bridge, non-public annotation members | 269 |

Where the 269 are, and what each needs — none of it reflection:

| Failures | TCK area | Needs |
|---:|---|---|
| 57 | `methodvalidation` | Executable inheritance rules (§5.6.5) recorded by the processor, executable descriptors on `IntrospectedBeanDescriptor` |
| 19 + 17 + 14 | `inheritance.method.invaliddeclarations`, `invalidconstraintdefinitions`, `crossparameter` | `ConstraintDeclarationException` / `ConstraintDefinitionException` verdicts recorded by the processor and thrown by the validator |
| 14 + 13 + 7 + 6 + 3 | the `groupconversion` packages | `@ConvertGroup` rules on parameters, return values and container elements, from the type-use metadata the visitor already walks |
| 20 + 3 + 3 + 3 + 1 | `xmlconfiguration.*` | XML-declared cascades and container element constraints bound through the introspection |
| 21 | `validation` | Field vs getter access semantics on reflective properties (a reflective property merges both; the specification validates them separately) |
| 8 + 8 + 9 | `application.method`, `containerelement`, `validatorresolution` | Cascaded constructor parameters, map key constraints, validator precedence for sub types |
| 7 | `metadata` | `ElementDescriptorTest`: `declaredOn` and `lookingAt` on executable descriptors |
| 5 | `valueextraction.builtin` | JavaFX holder types not introspected |
| 3 × 4 | `cdi`, `traversableresolver`, `constraints.application`, `validation.groupconversion.containerelement` | Bean context integration, `isReachable` call sequence |

The `validation` row is the one design question the bridge raises: a `ReflectionBeanIntrospection` property
merges the field, the getter and the setter, as a generated property does, while the specification treats a
constraint on the field and a constraint on the getter as two constrained elements. The generated path has
the same shape and passes because `@Introspected(accessKind = FIELD)` selects one; the reflective path will
need the same selection, or separate read paths for the two, before those 21 follow.

## What is deliberately not here

- `ReflectionValidator` is untouched in `validator` mode. Deleting it is the third step of the review's order
  and needs the processor work first; this branch makes `introspection` mode measurable so that the work can
  be tracked against the 269.
- `DefaultConstraintDescriptor` still reads composing constraints from the annotation type reflectively
  (step four of the order, the compile-time `@OverridesAttribute` transformer).
- The core branch is not pushed. Nothing here is published; the composite build is the only way to build it.
