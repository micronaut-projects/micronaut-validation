# A Kotlin backing field and a Java private field with its own getter describe the same way

**Status: open**

**Component:** `micronaut-core` — `inject-kotlin` (KSP introspections), `core` (`BeanPropertyMember`)
**Found on:** micronaut-core `5.3.0-SNAPSHOT`, by the differential campaign of micronaut-validation (`DifferentialCampaignTest`, corpus `Regression1`)

## What fails

```java
@Introspected(accessKind = {Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD}, visibility = Introspected.Visibility.ANY)
public static class Bean {
    @NotNull private String value;              // null
    public String getValue() { return "getter"; }
}
```

The guide says that, without `micronaut-validation-reflection`, direct access to an inaccessible field fails with a
`ValidationException`. However, the default profile validates `@NotNull` on the private field through the getter of the
same type. The getter returns `"getter"`, so the profile reports nothing. Hibernate Validator and the jakarta profile
report the null field.

## Why validation cannot tell

The validator reads such a field through a compatible generated getter (`ReflectionSupport.memberReader` /
`hasGeneratedGetter`). It does this because a Kotlin property introspected by KSP looks the same:
`@Introspected(accessKind = [FIELD], visibility = [ANY])` on a Kotlin class has an introspection with
`accessKind = [FIELD, METHOD]`, visibility `ANY`, and a single reflection-required `FIELD` member, with no getter
listed. That is identical to the Java bean above.

For the Kotlin property, the field is the backing field of the property's accessor, and reading it through the
accessor is correct. For the Java bean, the getter is independent code, so reading the field through it is wrong.
A change that stopped using the getter when field access is selected broke
`test-suite-kotlin` `GeneratedKotlinMetadataTest.privateFieldsAreReadByTheGeneratedDispatchWithoutTheCompanion`,
and it was reverted.

## Needed change

Either of these would work:

- Mark a field member as the backing field of the property's default accessor (KSP knows this; javac does not
  claim it), so the validator can read only a backing field through its accessor.
- Do not add `METHOD` to the access kinds of a KSP introspection that requested `FIELD` only.

With that signal, micronaut-validation fails as documented for an inaccessible Java field with an independent
getter, and `DifferentialCampaignTest` passes. The rest of the campaign already passes without the private-field case.
