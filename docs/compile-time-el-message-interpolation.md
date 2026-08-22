# Compiling the Jakarta EL of the constraint messages

A prototype layered on top of the `validation-el` module of
[#631](https://github.com/micronaut-projects/micronaut-validation/pull/631).

## What it replaces

`validation-el` interpolates the `${...}` of a constraint message through
`jakarta.el.ExpressionFactory`. On the branch of #631 that factory is `org.glassfish:jakarta.el`, which parses
the expression string on every violation and resolves every property and method through the reflective
`jakarta.el.BeanELResolver`.

This branch points it at
[micronaut-expression-language](https://github.com/micronaut-projects/micronaut-expression-language) instead,
and adds an annotation processor that compiles the expressions of the constraint messages while the type
declaring them is compiled.

## What is here

| Change | Where |
|---|---|
| The scan of the message templates, shared by the interpolator and the processor | `validation-el` → `MessageTemplates` |
| The interpolator uses `CompiledELContext` when the factory is the compiled one, so property access goes through the bean introspections | `validation-el` → `ElMessageInterpolator` |
| `org.glassfish:jakarta.el` → `micronaut-expression-language`, with the interpreter as the runtime fallback | `validation-el/build.gradle`, `validation-jakarta/build.gradle` |
| The processor compiling the expressions of the constraint messages | `validation-el-processor` → `ConstraintMessageELVisitor` |
| The sibling build of the unpublished `io.micronaut.el:*` artifacts | `settings.gradle` |

`jakarta-el-api` moves from `5.0.1` to `6.0.1`, which is what micronaut-expression-language builds against.
Jakarta Validation 3.1 is an EE 11 specification and EE 11's expression language is 6.0, so this is the
alignment the compliance stack wants anyway.

`micronaut` moves from `5.0.0` back to `5.1.11`, the value the `5.2.x` base branch carries. The
micronaut-expression-language build requires at least `5.1.1`, and `5.0.0` is below it. This is not only a
constraint of the expression language: at `5.1.1` five `@Validated` AOP tests of `micronaut-validation`
(`ValidatedSpec`, `RecordBeansSpec`, `WebSocketClientValidationSpec`) fail, and at `5.1.11` they pass again,
so the downgrade to `5.0.0` on the branch of #631 is what has to be revisited rather than the version this
prototype needs.

## How it works

A constraint message is a compile-time constant — the `message` member of the annotation, or its default. The
processor therefore knows, before the application runs, which expressions the interpolator will evaluate:

1. For every constraint on the type, its fields, its methods and their parameters, take the message template.
2. Run the message parameter pass of the section 5.3.1.1 over it, resolving `{param}` against the members of
   the constraint. This has to happen first, and has to happen the same way it happens at runtime: the
   parameter pass consumes `${value}` into `$5` and leaves `${value * 2}` alone, so it decides what the
   expression pass will see. `MessageTemplates` is shared between the two for that reason.
3. Compile each expression that remains with `ELParser`, `ELCompiler` and `ValueExpressionWriter` of
   micronaut-expression-language, and publish them through a generated `ELExpressionSource`.

At runtime `ElMessageInterpolator` calls `createValueExpression` with the same string, and
`CompiledExpressionFactory` returns the generated expression from a `switch` rather than parsing it.

```java
@Size(min = 1, max = 8, message = "the title is ${validatedValue.length()} long, not between {min} and {max}")
```

compiles to

```java
public final class Book$ValidationExpression0 extends CompiledValueExpression {

    public Book$ValidationExpression0() {
        super("${validatedValue.length()}", "${validatedValue[\"length\"]()}", java.lang.Object.class);
    }

    protected Object evaluate(ELContext context) {
        return ELResolution.invoke(context,
            ELResolution.resolveIdentifier(context, "validatedValue"), "length", new Object[]{});
    }
}
```

An expression the processor cannot compile is a warning, not an error: it is left out, and the interpreter
evaluates it at runtime exactly as it evaluates a template built at runtime.

## What is verified

```
./gradlew :micronaut-validation-el:test :micronaut-validation-el-processor:test
```

* The nine existing tests of `ElMessageInterpolatorTest` pass unchanged against
  micronaut-expression-language, so the engine swap preserves the behaviour #631 asserts.
* `MessageTemplatesTest` pins the cases on which the processor and the interpolator have to agree.
* `validation-el-processor` has **no interpreter on its test classpath**, so an expression that can be created
  at all is an expression that was compiled. `CompiledConstraintMessageTest` asserts that the expressions of
  the constraint messages resolve and that an undeclared one throws.
* `CompiledMessageInterpolationTest` runs the real `Validator` over a bean and checks the interpolated
  messages, still with no interpreter present.
* `IntrospectionDispatchTest` evaluates the compiled `${formatter.format('%.2f', validatedValue)}` against a
  context whose only resolver is `IntrospectionELResolver`, so the variable arity `format` is proven to go
  through the generated introspection and not through `jakarta.el.BeanELResolver`.

## What is not done

**Typed `validatedValue`.** The processor knows the type of the element being validated, so
`${validatedValue.length()}` could compile to `((String) …).length()` instead of a dynamic invocation. It does
not, because `ExpressionSourceWriter` keys its switch on `(expression string, expected type)` alone: two
constraint sites sharing an expression string over different validated types would resolve to whichever source
loaded first. Doing this needs the generated class name to travel on the constraint's annotation metadata —
`DefaultConstraintDescriptor` already holds the `AnnotationValue` and the `AnnotationMetadata` — so that the
interpolator asks for the expression of *this* constraint rather than for a string.

**Resource bundles.** Only the parameters that come from the members of the constraint are resolved at
compilation time. A template whose expressions only appear after a `ValidationMessages_xx.properties` lookup
still goes to the interpreter. The processor could read the bundles from the compile classpath with
`VisitorContext.getClasspathResources`, once per locale found there.

**Groovy and Kotlin.** The visitor writes bytecode for them, as the visitor of micronaut-expression-language
does, but only the Java path is exercised here.

**The artifacts are not published.** `io.micronaut.el:*` is `1.0.0-SNAPSHOT`, so `settings.gradle` includes
the sibling build when it is checked out next to this repository. Override the location with
`-PmicronautExpressionLanguagePath=…`, or drop the `includeBuild` once the module is released.
