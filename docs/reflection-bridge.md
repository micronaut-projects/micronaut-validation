# The reflection bridge: one reflective metadata, fed to one validator

Branch `claude/core-reflection-bridge`, rebased onto `5.2.x`. Pairs with the branch
`claude/reflection-bridge` in `../micronaut-core`, which is not released: until it is, its main sources are
**copied into this repository** under `validation/src/main/java/io/micronaut/inject/` (see "The copy of the
core bridge" below). There is no composite build any more, and the Jakarta EL implementation comes from the
published `io.micronaut.el:micronaut-jakarta-el:1.0.0`.

The shared bridge lives in micronaut-core; the validation modules consume it instead of converting
`java.lang.reflect` to Micronaut metadata by hand, and a mode switch makes the single-validator design
measurable against the TCK.

## What micronaut-core gained

All in `micronaut-inject`, so that both this repository and micronaut-jaxrs can depend on it; ODI's
`AnnotationReflection.toAnnotationValue` is the same conversion and can be retired for it.

| Type | What it is |
|---|---|
| `io.micronaut.inject.annotation.ReflectionAnnotationMetadataBuilder` | `AnnotationMetadata` from an `AnnotatedElement`, shaped like the generated one: meta-annotations as stereotypes recursively, repeatable annotations filed under their container (and the container registered so `getAnnotationValuesByType` finds them), defaults registered and reachable through `getDefaultValues`, class values as `AnnotationClassValue`, enum values as names, nested annotations as `AnnotationValue`, inherited class annotations present but not declared, members of non-public annotation types read. An `AnnotationValueProvider` instance returns its own value |
| `io.micronaut.inject.annotation.ReflectionAnnotationCustomizer` | A service SPI, the runtime counterpart of the annotation mappers and remappers of the processors, which cannot run at runtime. Receives the values of every annotation the builder converts |
| `io.micronaut.context.AnnotationReflectionUtils` (copied here as `io.micronaut.inject.reflection.ReflectionArguments`) | Its private `AnnotatedType` conversion is now public as `argumentOf(AnnotatedType \| Parameter \| Field)`, `argumentsOf(Executable)` and `returnArgumentOf(Method)`, carrying the type-use annotations of every level — `List<@Size String>` arrives as metadata on the type argument, as the processor emits it — and its metadata comes from the builder |
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

## The copy of the core bridge

Copied verbatim from `micronaut-core` branch `claude/reflection-bridge` into
`validation/src/main/java`, keeping the packages so that the package-private core API they use
(`AnnotationMetadataSupport.registerDefaultValues`, `registerRepeatableAnnotation`) stays reachable:

- `io/micronaut/inject/annotation/ReflectionAnnotationMetadataBuilder.java`, `ReflectionAnnotationCustomizer.java`
- `io/micronaut/inject/reflection/` — `ReflectionBeanIntrospection`, `ReflectionBeanIntrospector`,
  `ReflectiveIntrospection`, `SupplementedBeanIntrospection`, `ReflectionExecutableMethod`,
  `ReflectionExecutables`, `IntrospectedExecutableMethod`, `package-info`
- `io/micronaut/inject/reflection/ReflectionArguments.java` — `io.micronaut.context.AnnotationReflectionUtils`
  with the new argument factories, renamed and moved so that it does not shadow the released class; the
  callers in this repository use it for `resolveGenericToArgument` too

Two `checkstyle` fixes were needed for the stricter configuration here (two unused imports, one method
moved before the inner types); nothing else was changed. When the bridge is released in micronaut-core,
delete these files, restore `io.micronaut.context.AnnotationReflectionUtils` at the call sites, and the
branch is back to consuming core.

## What this repository does with it

| Before | After |
|---|---|
| `ReflectionValidationMetadataProvider` converted constraint annotations to metadata itself (own repeatable handling, a hand-added `Constraint` stereotype, no defaults) | Adds the constraint instances through `ReflectionAnnotationMetadataBuilder.add`; the container, the stereotypes and the defaults come out as generated |
| `DefaultValidatorConfiguration.argumentOf` / `annotationMetadataOf` converted the `AnnotatedType` of a value extractor itself | `AnnotationReflectionUtils.argumentOf`, `ReflectionAnnotationMetadataBuilder.build` |
| `ConstraintValidatorTargetResolver` walked `getGenericInterfaces()` to find the validated type | `AnnotationReflectionUtils.resolveGenericToArgument(type, ConstraintValidator.class).getTypeParameters()[1]` |
| `ValidationAnnotationRemapper` copied `@Constraint.validatedBy` into `$validatedBy` at compilation time only; reflective metadata had no validators | `ConstraintAnnotationCustomizer` in `validation-reflection` does the same at runtime, registered as a `ReflectionAnnotationCustomizer` |
| `validateConstructorParameters(Constructor, …)` used the one constructor the introspection knows, whatever constructor the caller named | Uses the introspection's arguments when they are the named constructor's, else `argumentsOf(constructor)` |
| `validateParameters(Object, Method, …)` / `validateReturnValue` returned an empty set when the declaring type is not a bean | Resolve through the bean definition, then the bean methods of the introspection (`IntrospectedExecutableMethod`), then `ReflectionExecutableMethod` — the API is defined on `Method`, so this last step is the reflection it imposes |

## One validator

There is no mode switch any more. `MicronautValidatorConfiguration` and the TCK harness build one
`DefaultValidator` over the generated introspections of the class loader, supplemented by a
`ReflectionBeanIntrospector` of micronaut-core for the types that have none; the constraint validators are
instantiated through the same introspector. The `validation-reflection` module and its `ReflectionValidator`
are gone; `ConstraintAnnotationCustomizer`, the one class of it the bridge needs, lives in
`io.micronaut.validation.validator.constraints`.

## Measurements

Jakarta Validation 3.1.1 TCK, full Jakarta profile, 1054 tests:

| Configuration | Failures |
|---|---:|
| `validator` mode, `spec-compliance` (`ReflectionValidator` over the reflection module) | 0 |
| default classes over a reflective introspection, at the start of this work | 383 |
| after the executable and constructor paths | 190 |
| after the hierarchy, declaration and definition rules | 91 |
| after the constraint containers and the per-member validation | 49 |
| after the XML arguments | 15 |
| **`DefaultValidator` alone, this branch** | **0** |

## What replaced the reflection module

Each `Reflection*` class had one job; the table says which default class does it now, and how.

| Was | Is | How |
|---|---|---|
| `ReflectionValidator` executable paths (`validateParameters`, `validateReturnValue`, constructors) | `DefaultValidator` + `ReflectiveExecutables` | A `Method` resolves to the bean definition of its declaring type, else to the `BeanMethod` of the introspection, else to a `ReflectionExecutableMethod` of the bridge; a `Constructor` to the `BeanConstructor` of the introspection, reflective introspections listing every constructor |
| `ReflectionMethodDeclarations`, `ReflectionGroupConversions` | `ExecutableHierarchy` | The declarations a method overrides or implements are read from the introspections of the super types — interfaces are introspected reflectively for this — merged for validation, and checked for the §5.6.5 rules; a `ReflectiveIntrospection.findDeclaredMethod` tells what a type declares itself, the rules fall back to a set difference when only merged metadata is known |
| `ReflectionConstraintDefinitions` | `ConstraintDefinitions` (validation module) | Same checks, run once per constraint type, only when `ValidatorConfiguration.isStrictConstraintDefinitions()` — the Jakarta provider turns it on, Micronaut keeps accepting constraints without `groups` or `payload` |
| `ReflectionValidator` composition rules | `DefaultConstraintDescriptor` | Mixed composition targets, overridden attribute types, `constraintIndex`, target propagation, validators of composing constraints |
| `ReflectionBeanMetadata` and the descriptor records | `IntrospectedBeanDescriptor`, `IntrospectedExecutableDescriptors` | Properties described from their `PropertyMember`s, executables from their hierarchy; `lookingAt(LOCAL_ELEMENT)` uses the declared metadata, `declaredOn` the member kind |
| field vs getter access, hidden super fields, interface groups | `DefaultValidator.visitPropertyMember` | A property of a reflective introspection is validated member by member, each read through the member declaring the constraints (`PropertyMember.read`), cascaded once; a member an interface declares is in the group of the interface; the traversable resolver is told the member kind |
| class-level constraints of super types | `ValidatorDeclarations.superIntrospections` | Each type validates the class-level constraints it declares, a type declaring none and inheriting all is skipped |
| redefined default group sequences of super types | `DefaultConstraintValidatorContext.findIsolatedGroupSequences` | A super type redefining the sequence validates its own members in its own passes (§5.6.3 isolation) |
| Bean Validation style constraint containers | `ConstraintContainers` | `X.List`, or any annotation holding constraints, read from the generated metadata, the contained values given the validators of the constraint definition |
| XML container element constraints, conversions, executables | `ValidationMetadataProvider.get*Argument` | The XML provider configures `Argument`s: type arguments merged with or replacing the declared annotations, `ignore-annotations` stripping what is not configured |
| `ReflectionConstraintValidatorFactory` | `DefaultInternalConstraintValidatorFactory` over the supplementing introspector | A validator class without a generated introspection is instantiated through its reflective one |
| `ReflectionValidator` container handling | `ContainerTypeArguments` | A container binding the type argument of a generic super type resolves it, annotations included; an unwrapped value keeps the type arguments of its container and reports it on the path |

What micronaut-core gained on the way, beyond the first section: `ReflectiveIntrospection.findDeclaredMethod`,
`PropertyMember.member()` and `PropertyMember.read()`, interfaces introspectable reflectively, a container
recognised by the `X.List` convention, the method metadata of `ReflectionExecutableMethod` read lazily.

The one core change that could not be copied — `AnnotationMetadataSupport.getAnnotationType(name,
classLoader)` answering with the class of the asking loader, since the registry is package-private state of
a released class — is answered here instead: `ConstraintContainers.constraintTypes` resolves every
constraint name through `ClassUtils.forName(name, classLoader)` rather than
`AnnotationMetadata#getAnnotationTypesByStereotype`, so a constraint deployed several times in different
class loaders is the one of the loader asking. Without it 12 of the 1054 TCK tests compare two classes of
the same name.

## What is deliberately not here

- `DefaultConstraintDescriptor` reads composing constraints and `ConstraintDefinitions` the members of a
  constraint annotation from the annotation type itself — annotation types are loaded anyway, the
  specification makes these runtime errors.
- The hierarchy of an executable is read from introspections at runtime, not recorded by the processor: a
  bean whose interfaces are not introspected gets the rules applied to what it can see, which is what the
  generated metadata already merges.
- The core branch `claude/reflection-bridge` is pushed to micronaut-core but nothing is published, so this
  branch carries a copy of its main sources instead of building against it.
