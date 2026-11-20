# The native image feature keeps a definition that failed to load and initializes it at build time

**Status: open**

**Component:** `micronaut-core` — `core` (`ServiceLoaderFeature`), `inject` (`AbstractInitializableBeanDefinitionAndReference`, `BeanInitializationFailure`)
**Found on:** micronaut-core `5.3.0-SNAPSHOT`, GraalVM 25.0.3, from the micronaut-validation native smoke (`:micronaut-validation:nativeSmokeBuild`)

## What fails

`HttpInterpolatorLocaleResolver` is declared with `@Requires(beans = HttpLocaleResolver.class)` only, and the
HTTP server it reads is `compileOnly`. On a native-image classpath without the server, the build stops with
this error:

```
An object of type 'io.micronaut.context.BeanInitializationFailure' was found in the image heap ...
  scanning root io.micronaut.context.BeanInitializationFailure: java.lang.NoClassDefFoundError: io/micronaut/http/server/util/locale/HttpLocaleR...
  embedded in io.micronaut.validation.validator.messages.$HttpInterpolatorLocaleResolver$Definition.<init>
```

Any application with such a bean on its classpath hits the same error, without the optional dependency.

## Cause

There are two gaps in `ServiceLoaderFeature.beforeAnalysis`, which decides which `BeanInfo` services to keep.

1. Since 5.3, the static initializer of a generated definition catches the failure to load and keeps a
   detached `BeanInitializationFailure`. `c.getDeclaredConstructor().newInstance()` therefore no longer throws
   for a definition whose classes are absent, and the "not loadable at runtime either, remove it" branch never
   runs. The definition is kept and passed to `initializeAtBuildTime(c)`. Its static failure is a `Throwable`
   of a type initialized at run time, and it lands in the image heap.
2. The `@Requires(beans = …)` branch discards its result:
   `ArrayUtils.concat(classNames, value.stringValues("beans"));` is never assigned, so the bean types are never
   checked with `findClassByName`. Had they been, this definition would have been removed, as one with
   `@Requires(classes = …)` is.

## Needed change

- Remove from the services any `BeanInfo` whose definition recorded a failed initialization. That is the state
  `AbstractInitializableBeanDefinitionAndReference.isClassAbsent()` describes, or any recorded failure, so that
  such a definition is never initialized at build time. At run time on the JVM the definition is already treated
  as absent.
- Assign the concatenation: `classNames = ArrayUtils.concat(classNames, value.stringValues("beans"));`.

Either change alone fixes this case. The first covers every optional type a definition references, including a
constructor parameter or a field type that no `@Requires` names.

## Validation side

`HttpInterpolatorLocaleResolver` now also declares `@Requires(classes = HttpLocaleResolver.class)`, as the other
beans that read an optional dependency do (`ConstraintExceptionHandler`, the JavaFX extractors). The feature
then removes it where the server is absent. `--initialize-at-build-time` is not used, since it would only move
the failure into the image.
