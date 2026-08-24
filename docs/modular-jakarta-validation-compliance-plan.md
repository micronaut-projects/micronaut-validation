# Modular Jakarta Validation Compliance Plan

Captured: 2026-05-15

Last updated: 2026-05-21

## Summary

Target Jakarta Validation 3.1 compliance through an opt-in Jakarta compliance stack, not by
making `micronaut-validation` heavier. The existing core module remains the
default reflection-free Micronaut implementation. Full Jakarta-compliant
behavior is provided by optional modules and a composed
`micronaut-validation-jakarta` aggregate.

## Module Topology

- Keep `micronaut-validation` and `micronaut-validation-processor` lean:
  - no XML parser wiring;
  - no Jakarta EL dependency;
  - no reflection metadata registry;
  - no `jakarta.validation.spi.ValidationProvider` service entry.
- Add optional modules:
  - `micronaut-validation-bootstrap`: `ValidationProvider`, `Configuration`,
    `BootstrapConfiguration`, spec `ValidatorFactory` bootstrap.
  - (removed) `micronaut-validation-reflection`: reflection metadata and reflective
    validator/value-extractor instantiation fallback.
  - `micronaut-validation-xml`: `META-INF/validation.xml` and constraint
    mapping XML support, using only JDK XML APIs.
  - `micronaut-validation-el`: Jakarta EL-backed message interpolation.
  - `micronaut-validation-jakarta`: aggregate module depending on bootstrap,
    reflection, XML, and EL for TCK/full compliance.
- Update the BOM so users can depend on individual feature modules or the
  aggregate.

## Implementation Strategy

- Introduce an internal metadata provider chain used by the validator:
  - generated Micronaut metadata first;
  - XML metadata next when `micronaut-validation-xml` is present;
  - types without a generated introspection are described by the reflection bridge of micronaut-core (the former `micronaut-validation-reflection` fallback is removed, see `reflection-bridge.md`).
- Reflection support is classpath-optional and also runtime-toggleable:
  - `micronaut.validator.spec.reflection.enabled=true` in the reflection and
    Jakarta aggregate modules;
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
    validation/Jakarta module packages only;
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
  - Jakarta compliance TCK profile using `micronaut-validation-jakarta` with no
    Micronaut-specific excludes.
- First fix the current method-validation TCK task so it actually executes.
- Add module-specific tests for bootstrap-only, reflection-only, XML-only,
  EL-only, and aggregate behavior.
- Add startup tests proving the bootstrap context only loads the intended
  validation/Jakarta beans.
- Use the generated TestNG single-test task for focused TCK development:
  `singleJakartaTck`, with `-PtckSingleClass=...` and optionally
  `-PtckSingleMethod=...` to run a single TCK class or method.
- Commit TCK progress in focused checkpoints: one passing TCK test when practical
  or a small wave of related TCK tests when the same implementation change fixes
  the group. Each checkpoint should include targeted module tests and the
  corresponding single TCK task evidence.
- `jakartaTck` runs the whole TCK against the micronaut-validation-jakarta
  aggregate with no exclusions and passes it, so it is the gate and any failure
  is a regression. There is no second TCK profile and no known-failure tracker:
  the lean module's own behaviour is covered by the unit tests of
  `micronaut-validation`.
- Final verification:
  - Jakarta compliance TCK with no unsupported-functionality excludes;
  - `./gradlew check`;
  - `./gradlew docs`;
  - `./gradlew japiCmp` for public API changes.

## Assumptions

- Full Jakarta Validation compliance means depending on
  `micronaut-validation-jakarta`.
- Default Micronaut users depending only on `micronaut-validation` should not pay
  for XML, EL, reflection fallback, or ServiceLoader bootstrap.
- Jakarta EL support is optional as a module, but included by the aggregate
  Jakarta module so the full TCK can pass.

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

## Follow-Up Execution Plan

Captured: 2026-05-21

Run the remaining work as four focused commit waves: API/QA refinements,
security hardening, TCK evidence, and user documentation. Keep
`micronaut-validation` lightweight and reflection-free by default. Spec-heavy
behavior stays in `validation-bootstrap`,
`validation-xml`, `validation-el`, or the aggregate `validation-jakarta`. New
public API stays binary-compatible and uses `@since 5.1`; any unavoidable
breaking change stops this track and becomes a 6.0.x decision.

### Wave 1: API, QA, And Documentation Sweep

- Minimize public API in implementation modules:
  - make classes package-private unless external loading requires `public`;
  - make classes `final` by default;
  - use sealed types only where they clearly improve internal modeling without
    widening API.
- Mark all `validation-xml`, `validation-el`, and `validation-bootstrap` classes
  as `@Internal` when they must remain public for ServiceLoader, reflective
  optional loading, or cross-module access.
- Review `validation-reflection` with the same surface-area rule: public only
  when required, `@Internal` when public, final or sealed where suitable.
- Sweep documentation over new code:
  - add or improve class-level Javadoc explaining purpose, module role, and why
    the type is internal;
  - add method and constructor Javadoc for public or protected members;
  - add concise comments only for non-obvious security, bootstrap, XML parsing,
    metadata merge, or reflection fallback logic.
- Remove avoidable duplication without broad rewrites:
  - centralize secure XML parser setup in `validation-xml`;
  - keep bootstrap/XML resource handling consistent;
  - avoid growing `micronaut-validation`.

### Wave 2: Security Hardening

- Add traversal and denial-of-service focused tests:
  - reject XML `DOCTYPE`, external entities, external DTD/schema access, and
    XInclude for both `validation.xml` and constraint mapping XML;
  - reject constraint mapping paths containing `..`, backslashes, URL schemes,
    empty names, or filesystem-style traversal;
  - continue accepting a single leading `/` as a classpath-root resource;
  - harden TCK archive extraction and copying so archive entries normalize under
    deployment temp directories.
- Recheck core reflection usage and document accepted cases:
  - `java.lang.reflect.Method` and `Constructor` API entrypoints are acceptable;
  - annotation-instance member reads are acceptable;
  - metadata discovery and reflective instantiation fallback must stay in
    `validation-reflection`.

### Wave 3: TCK Evidence

- Align the evidence run with Jakarta Validation 3.1 TCK `3.1.1`, and record
  distribution provenance plus checksum or signature metadata when available.
- Add `.github/workflows/tck.yml` modeled on the ODI evidence workflow:
  - `workflow_dispatch` first;
  - JDK 25 only, matching this repo's current CI;
  - run `:micronaut-tests:micronaut-jakarta-validation-tck:jakartaTck` with no
    excludes;
  - upload sanitized JUnit XML and an HTML/Markdown evidence bundle;
  - publish immutable `tck-results/<workflow-run-id>/` and
    `tck-results/latest/` pages to `gh-pages` only from `master`.
- Add `.github/scripts/collect-jakarta-validation-tck-evidence.sh` to record:
  - product/version/commit;
  - Java runtime;
  - TCK coordinates;
  - distribution download provenance;
  - sanitized test totals;
  - links to retained artifacts.
- Update `README.md` with the future stable TCK evidence URL after the workflow
  and page structure exists.
- Do not claim full upstream verification until the workflow has passed in the
  upstream repo.

### Wave 4: User Documentation

- Add a new guide section under `src/main/docs/guide` and wire it into
  `src/main/docs/guide/toc.yml`.
- Document the module model:
  - `micronaut-validation`: default lightweight compile-time/introspection
    implementation;
  - `micronaut-validation-bootstrap`: Jakarta `ValidationProvider` bootstrap;
  - `micronaut-validation-reflection`: optional reflection fallback;
  - `micronaut-validation-xml`: `META-INF/validation.xml` and constraint
    mappings;
  - `micronaut-validation-el`: Jakarta EL interpolation;
  - `micronaut-validation-jakarta`: aggregate Jakarta-compliance dependency.
- Update `quickStart.adoc` to replace stale "not fully compliant / use
  Hibernate Validator" wording with the new opt-in path:
  - use `micronaut-validation` for the lightweight default;
  - add `micronaut-validation-jakarta` for the Jakarta-compliant stack.
- Use Micronaut docs macros such as `dependency:` and avoid hand-written
  dependency tables.

### Verification And Commit Boundaries

- Commit wave 1: API surface, `@Internal`, final/sealed, code Javadocs, and QA
  structure refinements.
- Commit wave 2: security hardening and tests.
- Commit wave 3: TCK evidence workflow/script/README.
- Commit wave 4: user documentation.
- Final verification before handoff:
  - `./gradlew --no-daemon check docs japiCmp`;
  - `./gradlew --no-daemon :micronaut-tests:micronaut-jakarta-validation-tck:jakartaTck`.
