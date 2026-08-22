/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.validation.el.processor;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.el.ELExpressionSource;
import io.micronaut.el.parser.ELParser;
import io.micronaut.el.parser.ELParsingException;
import io.micronaut.el.processor.compiler.CompilationContext;
import io.micronaut.el.processor.compiler.ELCompilationException;
import io.micronaut.el.processor.compiler.ELCompiler;
import io.micronaut.el.processor.compiler.ELExpressionDefinition;
import io.micronaut.el.processor.writer.ExpressionSourceWriter;
import io.micronaut.el.processor.writer.ValueExpressionWriter;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.generator.bytecode.ByteCodeGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.validation.el.MessageTemplates;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The visitor compiling the Jakarta EL expressions of the constraint message templates declared by a type.
 *
 * <p>A message template is a compile time constant: it is the {@code message} member of a constraint
 * annotation, or the default of that member. The expressions it contains are therefore known before the
 * application runs, and are compiled here into the {@code jakarta.el.ValueExpression} implementations of
 * {@code micronaut-expression-language} rather than being parsed by the interpolator on every violation.</p>
 *
 * <p>The constraints are collected the way {@code DefaultConstraintDescriptor} resolves them at runtime, so
 * that the template the interpolator receives is the one compiled here: the attributes of a constraint are
 * its declared values completed by its defaults, the constraints of a container element are read from the
 * type arguments, and the constraints composing a custom constraint are read from its annotation type, with
 * the members annotated with {@code @OverridesAttribute} applied.</p>
 *
 * <p>The compiled expressions are published through a generated {@link ELExpressionSource}, which
 * {@code CompiledExpressionFactory} consults, so nothing else has to know that they exist:
 * {@code ElMessageInterpolator} keeps calling {@code ExpressionFactory.createValueExpression} with the same
 * string and gets the compiled expression back.</p>
 *
 * <p>An expression this visitor cannot compile is not an error. It is left out, and the interpolator falls
 * back to the interpreter, exactly as it does for a template built at runtime.</p>
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
public final class ConstraintMessageELVisitor implements TypeElementVisitor<Object, Object> {

    private static final String ANN_CONSTRAINT = "jakarta.validation.Constraint";
    private static final String ANN_OVERRIDES_ATTRIBUTE = "jakarta.validation.OverridesAttribute";
    private static final String MEMBER_MESSAGE = "message";
    private static final String EXPRESSIONS_SUFFIX = "$ValidationELExpressions";
    private static final String EXPRESSION_SUFFIX = "$ValidationExpression";
    /**
     * The depth at which the walk of the type arguments and of the composing constraints stops, so that a
     * self-referencing declaration cannot loop.
     */
    private static final int MAX_DEPTH = 8;

    private final Set<String> processed = new HashSet<>();

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of("jakarta.validation.*");
    }

    @Override
    public int getOrder() {
        return 20; // After ValidationVisitor, which inherits the constraints of the parent types
    }

    @Override
    public void start(VisitorContext visitorContext) {
        processed.clear();
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (!processed.add(element.getName())) {
            return;
        }
        Set<String> expressions = new ConstraintCollector(context).collect(element);
        if (expressions.isEmpty()) {
            return;
        }
        compile(element, context, expressions);
    }

    private void compile(ClassElement element, VisitorContext context, Set<String> expressions) {
        SourceGenerator sourceGenerator = generatorFor(context);
        CompilationContext compilationContext = new CompilationContext(
            context, Map.of(), Map.of(), List.of(), List.of(), Map.of());
        ELCompiler compiler = new ELCompiler(compilationContext);
        String prefix = element.getPackageName() + "." + element.getSimpleName();
        ClassElement objectType = ClassElement.of(Object.class);

        List<ExpressionSourceWriter.CompiledValue> compiled = new ArrayList<>(expressions.size());
        int index = 0;
        for (String expression : expressions) {
            String className = prefix + EXPRESSION_SUFFIX + index;
            try {
                ELExpressionDefinition definition = new ELExpressionDefinition(
                    expression, objectType, "EXPRESSION_" + index, ELParser.parse(expression));
                ClassDef classDef = ValueExpressionWriter.write(className, definition, compiler);
                // The visitor is isolating: the visited type is the one originating element of every file it
                // generates, whichever member, type argument or composing annotation the expression came from.
                sourceGenerator.write(classDef, context, element);
                compiled.add(new ExpressionSourceWriter.CompiledValue(definition, className));
                index++;
            } catch (ELParsingException | ELCompilationException e) {
                // The expression stays interpreted. The message is a warning rather than a failure because an
                // expression the compiler rejects may still be evaluable at runtime, and because a constraint
                // message must never break the build of the type declaring it.
                context.warn("The constraint message expression " + expression
                    + " was not compiled and will be evaluated at runtime: " + e.getMessage(), element);
            }
        }
        if (compiled.isEmpty()) {
            return;
        }
        String sourceClassName = prefix + EXPRESSIONS_SUFFIX;
        sourceGenerator.write(ExpressionSourceWriter.write(sourceClassName, compiled, List.of()), context, element);
        context.visitServiceDescriptor(ELExpressionSource.class.getName(), sourceClassName, element);
    }

    /**
     * A Java build gets readable sources, the other languages get bytecode, as in the visitor of
     * micronaut-expression-language.
     */
    private static SourceGenerator generatorFor(VisitorContext context) {
        if (context.getLanguage() == VisitorContext.Language.JAVA) {
            return SourceGenerators.findByLanguage(VisitorContext.Language.JAVA)
                .orElseGet(ByteCodeGenerator::new);
        }
        return new ByteCodeGenerator();
    }

    /**
     * Collects the expressions of the constraints of a type, resolving them as the runtime descriptor does.
     */
    private static final class ConstraintCollector {

        private final VisitorContext context;
        private final Set<String> expressions = new LinkedHashSet<>();

        ConstraintCollector(VisitorContext context) {
            this.context = context;
        }

        /**
         * Walks the type, its fields, its methods, their return types and parameters, and the parameters of its
         * primary constructor. The whole type is walked here rather than one element at a time, because the
         * visitor is isolating: everything a type contributes must be generated while that type is visited.
         */
        Set<String> collect(ClassElement element) {
            collectConstraints(element.getAnnotationMetadata());
            for (FieldElement field : element.getEnclosedElements(ElementQuery.ALL_FIELDS)) {
                collectTyped(field);
            }
            for (MethodElement method : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
                collectConstraints(method.getAnnotationMetadata());
                collectTyped(method.getReturnType());
                for (ParameterElement parameter : method.getParameters()) {
                    collectTyped(parameter);
                }
            }
            element.getPrimaryConstructor().ifPresent(constructor -> {
                for (ParameterElement parameter : constructor.getParameters()) {
                    collectTyped(parameter);
                }
            });
            return expressions;
        }

        /**
         * The constraints of an element and of the container elements of its type: {@code List<@Size String>}
         * declares its constraint on the type argument, which is walked like the element itself.
         */
        private void collectTyped(TypedElement element) {
            collectConstraints(annotationMetadataOf(element));
            collectTypeArguments(element.getGenericType(), 0);
        }

        private void collectTypeArguments(ClassElement type, int depth) {
            if (depth >= MAX_DEPTH) {
                return;
            }
            for (ClassElement typeArgument : type.getTypeArguments().values()) {
                collectConstraints(typeArgument.getTypeAnnotationMetadata());
                collectTypeArguments(typeArgument, depth + 1);
            }
        }

        private static AnnotationMetadata annotationMetadataOf(TypedElement element) {
            return element instanceof ClassElement classElement
                ? classElement.getTypeAnnotationMetadata()
                : element.getAnnotationMetadata();
        }

        /**
         * The constraints present on an element.
         *
         * <p>Micronaut flattens the stereotypes onto the element, so the {@code @Size} composing a custom
         * constraint is listed next to the constraint itself, with the raw values of the meta-annotation, and
         * nothing in the metadata tells the two apart: a repeatable constraint is filed under its container, so
         * neither {@code hasAnnotation} nor {@code hasStereotype} answers for it. The composing constraints are
         * therefore recognised structurally, as the names the annotation types of the other constraints of
         * the element compose, and are taken from those annotation types, where the overrides apply. A
         * constraint declared directly next to one composing it is left to the interpreter, which only costs
         * the compilation of its message.</p>
         */
        private void collectConstraints(AnnotationMetadata metadata) {
            List<String> names = metadata.getAnnotationNamesByStereotype(ANN_CONSTRAINT);
            Set<String> composed = new HashSet<>();
            for (String name : names) {
                collectComposedNames(name, composed, 0);
            }
            for (String name : names) {
                if (composed.contains(name)) {
                    continue;
                }
                for (AnnotationValue<?> constraint : valuesOf(metadata, name)) {
                    Map<CharSequence, Object> attributes = attributesOf(constraint, metadata.getDefaultValues(name));
                    collectMessage(attributes);
                    collectComposing(name, attributes, 0);
                }
            }
        }

        /**
         * The names of the constraints composing a constraint, recursively.
         */
        private void collectComposedNames(String constraintName, Set<String> composed, int depth) {
            if (depth >= MAX_DEPTH) {
                return;
            }
            ClassElement constraintType = context.getClassElement(constraintName).orElse(null);
            if (constraintType == null) {
                return;
            }
            for (String composing : constraintType.getAnnotationMetadata().getAnnotationNamesByStereotype(ANN_CONSTRAINT)) {
                if (composed.add(composing)) {
                    collectComposedNames(composing, composed, depth + 1);
                }
            }
        }

        /**
         * The values of an annotation: {@code getAnnotationValuesByName} serves the repeatable annotations
         * and is empty for the others, which {@code getAnnotation} serves.
         */
        private static List<? extends AnnotationValue<?>> valuesOf(AnnotationMetadata metadata, String name) {
            List<? extends AnnotationValue<?>> values = metadata.getAnnotationValuesByName(name);
            if (!values.isEmpty()) {
                return values;
            }
            AnnotationValue<?> single = metadata.getAnnotation(name);
            return single == null ? List.of() : List.of(single);
        }

        /**
         * The constraints composing a constraint, read from its annotation type as
         * {@code DefaultConstraintDescriptor.composingAnnotations} reads them at runtime: every constraint
         * present on the type, a repeated one counted by its index, with the attributes of the parent that
         * {@code @OverridesAttribute} redirects to it, and recursively their own composing constraints.
         */
        private void collectComposing(String constraintName, Map<CharSequence, Object> parentAttributes, int depth) {
            if (depth >= MAX_DEPTH) {
                return;
            }
            ClassElement constraintType = context.getClassElement(constraintName).orElse(null);
            if (constraintType == null) {
                return;
            }
            AnnotationMetadata metadata = constraintType.getAnnotationMetadata();
            Map<String, Integer> indexes = new HashMap<>();
            for (String composing : metadata.getAnnotationNamesByStereotype(ANN_CONSTRAINT)) {
                for (AnnotationValue<?> value : valuesOf(metadata, composing)) {
                    int index = indexes.merge(composing, 0, (previous, ignored) -> previous + 1);
                    Map<CharSequence, Object> attributes = attributesOf(value, metadata.getDefaultValues(composing));
                    applyOverrides(constraintType, composing, index, parentAttributes, attributes);
                    collectMessage(attributes);
                    collectComposing(composing, attributes, depth + 1);
                }
            }
        }

        /**
         * Applies the members of the parent constraint annotated with {@code @OverridesAttribute} to the
         * attributes of the composing constraint they target.
         */
        private static void applyOverrides(ClassElement parentType,
                                           String composing,
                                           int composingIndex,
                                           Map<CharSequence, Object> parentAttributes,
                                           Map<CharSequence, Object> attributes) {
            for (MethodElement member : parentType.getEnclosedElements(ElementQuery.ALL_METHODS)) {
                Object value = parentAttributes.get(member.getName());
                if (value == null) {
                    continue;
                }
                for (AnnotationValue<?> override : member.getAnnotationValuesByName(ANN_OVERRIDES_ATTRIBUTE)) {
                    if (!composing.equals(override.stringValue("constraint").orElse(null))) {
                        continue;
                    }
                    int constraintIndex = override.intValue("constraintIndex").orElse(-1);
                    if (constraintIndex != -1 && constraintIndex != composingIndex) {
                        continue;
                    }
                    String name = override.stringValue("name").filter(s -> !s.isEmpty()).orElse(member.getName());
                    attributes.put(name, value);
                }
            }
        }

        /**
         * The attributes of a constraint as {@code ConstraintDescriptor.getAttributes()} returns them: the
         * declared values completed by the defaults of the annotation type. The defaults are taken from the
         * metadata of the element when the annotation value does not carry them, which is the case of an
         * annotation type compiled in the same build.
         */
        private static Map<CharSequence, Object> attributesOf(AnnotationValue<?> constraint,
                                                              Map<CharSequence, Object> metadataDefaults) {
            Map<CharSequence, Object> attributes = new LinkedHashMap<>(constraint.getValues());
            Map<CharSequence, Object> defaults = constraint.getDefaultValues();
            if (defaults == null || defaults.isEmpty()) {
                defaults = metadataDefaults;
            }
            if (defaults != null) {
                defaults.forEach(attributes::putIfAbsent);
            }
            return attributes;
        }

        /**
         * Runs the message parameter pass of the specification over the template, resolving the parameters
         * against the attributes of the constraint, and keeps the expressions the interpolator will be left to
         * evaluate.
         *
         * <p>A parameter the compilation cannot resolve — one coming from a resource bundle chosen by the
         * locale of the request, for instance — is left in place, exactly as the interpolator leaves an
         * unknown parameter in place. The template then either yields the same expressions it will yield at
         * runtime, or yields an expression that is never asked for, which costs a generated class and nothing
         * else.</p>
         */
        private void collectMessage(Map<CharSequence, Object> attributes) {
            if (!(attributes.get(MEMBER_MESSAGE) instanceof String message)) {
                return;
            }
            String resolved = MessageTemplates.resolveParameters(message,
                name -> Optional.ofNullable(attributes.get(name)));
            expressions.addAll(MessageTemplates.expressionsOf(resolved));
        }
    }
}
