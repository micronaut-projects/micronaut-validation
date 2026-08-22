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
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.generator.bytecode.ByteCodeGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.validation.el.MessageTemplates;

import java.util.ArrayList;
import java.util.HashSet;
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
    private static final String MEMBER_MESSAGE = "message";
    private static final String EXPRESSIONS_SUFFIX = "$ValidationELExpressions";
    private static final String EXPRESSION_SUFFIX = "$ValidationExpression";

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
        Set<String> expressions = collectExpressions(element);
        if (expressions.isEmpty()) {
            return;
        }
        compile(element, context, expressions);
    }

    /**
     * Collects the expressions of every constraint declared by the type, by its fields, by its methods and by
     * their parameters.
     *
     * <p>The whole type is walked here rather than one element at a time, because the visitor is isolating:
     * everything a type contributes must be generated while that type is being visited.</p>
     */
    private Set<String> collectExpressions(ClassElement element) {
        Set<String> expressions = new LinkedHashSet<>();
        collectExpressions(element.getAnnotationMetadata(), expressions);
        for (FieldElement field : element.getEnclosedElements(ElementQuery.ALL_FIELDS)) {
            collectExpressions(field.getAnnotationMetadata(), expressions);
        }
        for (MethodElement method : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
            collectExpressions(method.getAnnotationMetadata(), expressions);
            collectExpressions(method.getReturnType().getAnnotationMetadata(), expressions);
            for (ParameterElement parameter : method.getParameters()) {
                collectExpressions(parameter.getAnnotationMetadata(), expressions);
            }
        }
        element.getPrimaryConstructor().ifPresent(constructor -> {
            for (ParameterElement parameter : constructor.getParameters()) {
                collectExpressions(parameter.getAnnotationMetadata(), expressions);
            }
        });
        return expressions;
    }

    private void collectExpressions(AnnotationMetadata annotationMetadata, Set<String> expressions) {
        for (String name : annotationMetadata.getAnnotationNamesByStereotype(ANN_CONSTRAINT)) {
            for (AnnotationValue<?> constraint : annotationMetadata.getAnnotationValuesByName(name)) {
                messageOf(constraint).ifPresent(message ->
                    expressions.addAll(expressionsOf(message, constraint)));
            }
        }
    }

    private Optional<String> messageOf(AnnotationValue<?> constraint) {
        Optional<String> declared = constraint.stringValue(MEMBER_MESSAGE);
        if (declared.isPresent()) {
            return declared;
        }
        Map<CharSequence, Object> defaults = constraint.getDefaultValues();
        Object defaultMessage = defaults == null ? null : defaults.get(MEMBER_MESSAGE);
        return defaultMessage instanceof String message ? Optional.of(message) : Optional.empty();
    }

    /**
     * Runs the message parameter pass of the specification over the template, resolving the parameters against
     * the members of the constraint, and returns the expressions the interpolator will be left to evaluate.
     *
     * <p>A parameter the compilation cannot resolve — one coming from a resource bundle chosen by the locale of
     * the request, for instance — is left in place, exactly as the interpolator leaves an unknown parameter in
     * place. The template then either yields the same expressions it will yield at runtime, or yields an
     * expression that is never asked for, which costs a generated class and nothing else.</p>
     */
    private List<String> expressionsOf(String message, AnnotationValue<?> constraint) {
        Map<CharSequence, Object> members = constraint.getValues();
        String resolved = MessageTemplates.resolveParameters(message,
            name -> Optional.ofNullable(members.get(name)));
        return MessageTemplates.expressionsOf(resolved);
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
}
