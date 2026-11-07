# Smaller processor and metadata gaps

**Status: proposal** (drafted 2026-11-07 from micronaut-validation `codex/validation-core53-hardening` 88ce0908)
**Component:** micronaut-core `core-processor`, `core`
**Checked against:** micronaut-core `origin/5.3.x`

Each of these is small; together they remove ~300 lines of workarounds from micronaut-validation.

## Annotation builder registration limits

`VisitorContext.registerAnnotationBuilder` names the builder `Owner$fully_qualified_Annotation$AnnotationBuilder`
and writes a service entry under that name; past 255 characters the compilation fails on the file system.
It also accepts annotations generated code cannot implement (a non-public annotation or member type).
micronaut-validation guards both (`ValidationVisitor.registerAnnotationBuilder`, `buildable`, `MAX_FILE_NAME`).
Proposal: core hashes or truncates the owner part, reports an unbuildable annotation itself, and can register
the builders of the annotations an annotation composes:
`boolean registerAnnotationBuilder(ClassElement annotation, boolean includeComposed, Element... originating)`.

## `getDeclaredMetadata()` of merged metadata

`AnnotationMetadataHierarchy.getDeclaredMetadata()` returns the first level, which can itself be a hierarchy, so
`BeanMethod.getDeclaredMethodAnnotationMetadata()` already calls it twice, and validation's
`ExecutableHierarchy.declaredOf` rebuilds a hierarchy to keep the repeated annotations. Proposal: a method with a
defined meaning - the element's own declaration, nested hierarchies flattened, repeatable containers kept -
under a new name (`getOwnMetadata()`) to avoid changing `getDeclaredMetadata()` for existing readers.

## Lookups by name

`ValidationMetadataSupport.type(name, loader)` scans `findIntrospections` to resolve a class by name without
`Class.forName`; `annotationMembers`/`standardDefaults` hand-maintain the members of the 22 standard constraints.
Proposal: `BeanIntrospector.findIntrospectedType(String name)` (the index exists in `DefaultBeanIntrospector`)
and `AnnotationBuilder.members()` (name, type, default) from the generated builder.

## Effective annotation value equality

`AnnotationValue.equals` compares declared members only and `hashCode` hashes the whole member map, so two
occurrences differing only in a retained internal member (`$validatedBy`) are unequal, and two equal in effect
(one declaring a default explicitly) are unequal too. Validation keys constraints by a hand-built string
(`ConstraintAnnotationKey`, 134 lines). Proposal: `AnnotationValue.effectiveEquals(other)` /
`effectiveHashCode()` over declared and default members, excluding `$`-prefixed members.

## Leftover fallbacks once 5.3 is the floor

All core changes validation's comments point to are on 5.3.x: #13924 (`AnnotationElement.getMembers`), #14055
(typed builder members), #13926/#14064 (type hierarchy records). Validation can drop the `members.isEmpty()`
fallback in `ConstraintDefinitionMetadata.members()` and the `UnsupportedOperationException` catch in
`RuntimeValidationAccess.annotationAttributes`.
