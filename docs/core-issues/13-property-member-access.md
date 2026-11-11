# Tell whether a property member is read through reflection

**Status: adopted** (core #14320, merge 9c1917e100; adopted 2026-11-11)
**Component:** micronaut-core `core` (`BeanPropertyMember`), `core-processor`
**Checked against:** micronaut-core `origin/5.3.x`

## The gap

Since #12918 a property lists its members, and core's generated dispatch already decides whether a field is
read directly or through reflection. That decision is not exposed. micronaut-validation re-derives it in its
processor (`IntrospectedValidationIndexesVisitor.recordFields`) by asking
`field.isReflectionRequired(ClassElement.of(name + "$ValidationAccess"))` with a made-up calling type, stores
it in a synthetic `@ValidationField(reflection, property)`, marks record accessors with
`@ValidationRecordAccessor` so a record component is not validated twice, and writes its own
`reflect-config.json` for `@ReflectiveAccess` fields.

## Proposed API

```java
public interface BeanPropertyMember<B, T> {
    /** Whether the generated dispatch reads this member through reflection. */
    boolean isReflectionRequired();
    /** Whether the member is another face of a declaration already listed, such as a record accessor of its component. */
    boolean isSynthetic();
}
```

plus the GraalVM reflection configuration for such members written by core.

## What micronaut-validation deletes

`ValidationField`, `ValidationRecordAccessor`, `recordFields`, `registerAnnotatedFields`, both `readMember`
overloads and the field cache of the reflection module (~120 lines).
