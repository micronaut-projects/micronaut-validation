# Find the generated executable for a java.lang.reflect.Method

**Status: proposal** (drafted 2026-11-07 from micronaut-validation `codex/validation-core53-hardening` 88ce0908)
**Component:** micronaut-core `inject` (`ExecutionHandleLocator`), `core` (`BeanIntrospection`), `reflection`
**Checked against:** micronaut-core `origin/5.3.x`

## The gap

`Validator.forExecutables().validateParameters(bean, Method, …)` names a method by its `Method`.
micronaut-validation maps it to generated metadata itself: `CompileTimeSupport.executableMethod`/`beanConstructor`
plus `IntrospectedExecutable` and `CallerSuppliedExecutable` (~250 lines). Core's
`reflection/ReflectionExecutables.executableMethod` is the same code with a reflective tail, and has its own
`IntrospectedExecutableMethod`.

## Proposed API

```java
// inject
interface ExecutionHandleLocator {
    <T, R> Optional<ExecutableMethod<T, R>> findExecutableMethod(Class<T> declaringType, String name, Class<?>[] parameterTypes, boolean exactDeclaringType);
}
// core
interface BeanIntrospection<T> {
    Optional<BeanMethod<T, ?>> findBeanMethod(String name, Class<?>... parameterTypes);
}
```

with `IntrospectedExecutableMethod` public in inject; `ReflectionExecutables` keeps only the reflective fallback.

## Other users

micronaut-data repository proxies, micronaut-http route binding when handed a `Method`.
