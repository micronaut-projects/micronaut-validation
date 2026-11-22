# An annotation type a visitor mutated adds its declared stereotypes again, past the indexed aliases

**Status: open**

**Component:** `micronaut-core` — `core-processor` (`AbstractAnnotationMetadataBuilder.addStereotypes`, `getAddedStereotypes`)
**Found on:** micronaut-core `5.3.0-SNAPSHOT`, measured from micronaut-validation `claude/adopt-core53-merges` (b56f311f)

## What fails

The Jakarta Validation TCK case `ConstraintCompositionTest.testConstraintIndexWithListContainer` reports a
third `@Pattern` violation. The constraint `FrenchZipcodeListContainer` composes
`@Pattern.List({@Pattern(regexp = "....."), @Pattern(regexp = "bar")})` and overrides the second occurrence
with `@OverridesAttribute(constraint = Pattern.class, name = "regexp", constraintIndex = 1)`. Validation
maps that override to `@AliasFor(annotation = Pattern.class, member = "regexp", index = 1)`.

The TCK harness `@ClassImport`s the nested types of the archive, so it imports the constraint annotation type
too. With the import, the getter's metadata holds the `@Pattern` values `[....., \d*, bar]`. Without it, the
values are `[....., \d*]`, which is correct. The bean descriptor path reads the same metadata, so validation
and metadata both report the extra `bar` occurrence. Nothing on the validation side differs between the two
compilations except the `io.micronaut.inject.ast.ImportedClass` stereotype that the import adds.

## Reproduction with core alone

```java
package test;

import io.micronaut.context.annotation.AliasFor;
import io.micronaut.core.annotation.Introspected;
import java.lang.annotation.*;

@Repeatable(Tags.class)
@Retention(RetentionPolicy.RUNTIME)
@interface Tag { String value(); }

@Retention(RetentionPolicy.RUNTIME)
@interface Tags { Tag[] value(); }

@Tag("a")
@Tag("b")
@Retention(RetentionPolicy.RUNTIME)
@interface Composite {
    @AliasFor(annotation = Tag.class, member = "value", index = 1)
    String second() default "x";
}

@Introspected
class Bean {
    @Composite(second = "x")
    private String value;
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}

// remove this type and the result is correct
@io.micronaut.context.annotation.ClassImport(classes = Composite.class)
class Imports {}
```

Built with `AbstractTypeElementSpec.buildBeanIntrospection('test.Bean', …)`, the values of
`introspection.getRequiredProperty("value", String).getAnnotationMetadata().getAnnotationValuesByName("test.Tag")`
are as follows:

| Source | Values |
| --- | --- |
| without `Imports` | `[a, x]` |
| with `Imports` | `[a, x, b]` |

## Cause

`VisitorUtils` annotates every imported `ClassElement` with `@ImportedClass`. For an annotation type, that
records mutated metadata for the type. In `AbstractAnnotationMetadataBuilder.addStereotypes`, the stereotypes
are built from the annotation type's declaration. `applyIntroducedAliases` then applies the aliases, the
indexed ones included. After that, `getAddedStereotypes(context, annotationType)` is concatenated. It returns
every annotation and stereotype of the annotation type's mutated metadata, not only what the mutation added.
That includes the declared `@Tag("a")`/`@Tag("b")` as written, which the aliases never touch, so a raw copy of
the repeated stereotype is appended. The merge of repeated values deduplicates `a` and keeps `b`.

A non-indexed alias hides the same duplication when the duplicate's values are identical, or when the raw
values happen to pass. Validation's `@Size(min/max)` override is one such case.

## Needed change

`getAddedStereotypes` should contribute only the annotations a visitor added to the annotation type. That
means leaving out those the type declares, which `extractStereotypes` has already processed with the aliases
applied. Alternatively, `addStereotypes` could apply the introduced aliases after the added stereotypes are
concatenated, with occurrences that are equal by name and values merged. The first option matches what the
method is for: the added stereotypes here should be just `@ImportedClass`.

A core test is the reproduction above, asserting `[a, x]` with the `@ClassImport` present.

## Validation side

There is nothing to change in validation. The local reproduction is
`validation/src/test/java/io/micronaut/validation/composition/ClassImportedIndexOverrideTest.java`, with its
types in `.../composition/imported/`. It is uncommitted, and it fails until core changes.
