# Modular Jakarta Validation Compliance Plan

Captured: 2026-05-15

## Summary

Target Jakarta Validation 3.1 compliance through an opt-in spec stack, not by
making `micronaut-validation` heavier. The existing core module remains the
default reflection-free Micronaut implementation. Full spec behavior is provided
by optional modules and a composed `micronaut-validation-spec` aggregate.

## Module Topology

- Keep `micronaut-validation` and `micronaut-validation-processor` lean:
  - no XML parser wiring;
  - no Jakarta EL dependency;
  - no reflection metadata registry;
  - no `jakarta.validation.spi.ValidationProvider` service entry.
- Add optional modules:
  - `micronaut-validation-bootstrap`: `ValidationProvider`, `Configuration`,
    `BootstrapConfiguration`, spec `ValidatorFactory` bootstrap.
  - `micronaut-validation-reflection`: reflection metadata and reflective
    validator/value-extractor instantiation fallback.
  - `micronaut-validation-xml`: `META-INF/validation.xml` and constraint
    mapping XML support, using only JDK XML APIs.
  - `micronaut-validation-el`: Jakarta EL-backed message interpolation.
  - `micronaut-validation-spec`: aggregate module depending on bootstrap,
    reflection, XML, and EL for TCK/full compliance.
- Update the BOM so users can depend on individual feature modules or the
  aggregate.

## Implementation Strategy

- Introduce an internal metadata provider chain used by the validator:
  - generated Micronaut metadata first;
  - XML metadata next when `micronaut-validation-xml` is present;
  - reflection fallback last when `micronaut-validation-reflection` is present.
- Reflection support is classpath-optional and also runtime-toggleable:
  - `micronaut.validator.spec.reflection.enabled=true` in the reflection/spec
    modules;
  - `micronaut.validator.spec.reflection-warnings.enabled=true` by default;
  - warnings are emitted once per reflective class/member/reason.
- Core APIs should implement cheap spec-compatible accessors where possible, but
  heavy compliance behavior lives in optional modules.
- Message interpolation remains lightweight in core; EL expressions are
  evaluated only when `micronaut-validation-el` is present.
- XML support is isolated in `micronaut-validation-xml`; core and bootstrap do
  not parse XML unless that module is on the classpath.

## Bootstrap Strategy

- `micronaut-validation-bootstrap` provides the ServiceLoader provider used by
  `Validation.buildDefaultValidatorFactory()`.
- Its private context follows the Micronaut Serialization
  `ObjectMapper.getDefault()` pattern:
  - `ApplicationContext.builder()`;
  - narrow `beansPredicate` and `beanConfigurationsPredicate` to
    validation/spec module packages only;
  - disable events, eager beans, package deduction, cloud deduction, bootstrap
    environment, and default property sources;
  - use the thread context classloader;
  - add only explicit configuration properties and XML-derived properties.
- The bootstrap context must not scan arbitrary application packages by default.
  App-level Micronaut DI remains the normal path for injected `Validator` and
  `ValidatorFactory`.

## Test Plan

- Split TCK execution into:
  - core TCK profile proving existing lean behavior;
  - spec TCK profile using `micronaut-validation-spec` with no
    Micronaut-specific excludes.
- First fix the current method-validation TCK task so it actually executes.
- Add module-specific tests for bootstrap-only, reflection-only, XML-only,
  EL-only, and aggregate behavior.
- Add startup tests proving the bootstrap context only loads the intended
  validation/spec beans.
- Final verification:
  - spec TCK with no unsupported-functionality excludes;
  - `./gradlew check`;
  - `./gradlew docs`;
  - `./gradlew japiCmp` for public API changes.

## Assumptions

- Full spec compliance means depending on `micronaut-validation-spec`.
- Default Micronaut users depending only on `micronaut-validation` should not pay
  for XML, EL, reflection fallback, or ServiceLoader bootstrap.
- Jakarta EL support is optional as a module, but included by the aggregate spec
  module so the full TCK can pass.

## Versioning Constraint

- Target this work for Micronaut Validation 5.1, the next minor release.
- Use `@since 5.1` for all newly introduced public API types, methods, and
  fields.
- Preserve semantic-versioning compatibility for existing public APIs. Public API
  changes must be additive or implemented as default methods where needed so
  existing user implementations remain compatible.
- If full compliance requires a breaking change that cannot be avoided with an
  additive design, defer that piece to a 6.0.x line instead of merging it into
  5.1.
