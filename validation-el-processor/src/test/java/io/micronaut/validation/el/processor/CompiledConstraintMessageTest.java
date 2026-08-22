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

import io.micronaut.el.CompiledELContext;
import io.micronaut.el.CompiledExpressionFactory;
import jakarta.el.ELException;
import jakarta.el.ExpressionFactory;
import jakarta.el.ValueExpression;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * There is no interpreter on the classpath of this module, so an expression that can be created at all is an
 * expression the annotation processor compiled.
 */
class CompiledConstraintMessageTest {

    @Test
    void theFactoryIsTheCompiledOne() {
        assertInstanceOf(CompiledExpressionFactory.class, ExpressionFactory.newInstance());
    }

    @Test
    void theExpressionsOfTheConstraintMessagesAreCompiled() {
        ValueExpression expression = ExpressionFactory.newInstance()
            .createValueExpression(new CompiledELContext(), "${validatedValue.length()}", Object.class);

        assertTrue(expression.getClass().getName().contains("$ValidationExpression"),
            () -> "Expected a generated expression but got " + expression.getClass().getName());
    }

    @Test
    void theMessageParametersAreSubstitutedBeforeTheExpressionsAreCollected() {
        // The declared message is "${formatter.format('%.2f', validatedValue)} is below {value}": the {value}
        // parameter is not part of any expression, and the expression that remains is the compiled one.
        ValueExpression expression = ExpressionFactory.newInstance()
            .createValueExpression(new CompiledELContext(), "${formatter.format('%.2f', validatedValue)}", Object.class);

        assertTrue(expression.getClass().getName().contains("$ValidationExpression"));
    }

    @Test
    void anExpressionNoConstraintDeclaresIsNotCompiled() {
        ExpressionFactory factory = ExpressionFactory.newInstance();
        CompiledELContext context = new CompiledELContext();

        ELException e = assertThrows(ELException.class,
            () -> factory.createValueExpression(context, "${validatedValue.undeclared()}", Object.class));

        assertTrue(e.getMessage().contains("was not compiled"));
    }

    @Test
    void aCompiledExpressionEvaluatesAgainstTheInterpolationVariables() {
        ExpressionFactory factory = ExpressionFactory.newInstance();
        CompiledELContext context = new CompiledELContext();
        context.getVariableMapper().setVariable("validatedValue",
            factory.createValueExpression("a long title", Object.class));

        Object value = factory.createValueExpression(context, "${validatedValue.length()}", Object.class)
            .getValue(context);

        assertEquals(12L, ((Number) value).longValue());
    }
}
