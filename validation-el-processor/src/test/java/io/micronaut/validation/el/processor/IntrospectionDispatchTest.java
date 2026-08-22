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

import io.micronaut.el.resolver.IntrospectionELResolver;
import io.micronaut.validation.el.ElMessageInterpolator;
import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.ExpressionFactory;
import jakarta.el.FunctionMapper;
import jakarta.el.ValueExpression;
import jakarta.el.VariableMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The context of these tests has a single resolver, the introspection one, and no interpreter is on the
 * classpath. An expression that evaluates here was compiled, and every property and method it touches was
 * dispatched through a generated introspection: {@code jakarta.el.BeanELResolver} is not in the chain, so a
 * reflective fallback would fail with {@code MethodNotFoundException} rather than silently succeed.
 */
class IntrospectionDispatchTest {

    @Test
    void theVariableArityFormatterIsDispatchedThroughTheIntrospection() {
        ExpressionFactory factory = ExpressionFactory.newInstance();
        IntrospectionOnlyContext context = new IntrospectionOnlyContext();
        context.set("validatedValue", factory.createValueExpression(0.5d, Object.class));
        context.set("formatter", factory.createValueExpression(
            new ElMessageInterpolator.LocaleFormatter(Locale.ENGLISH), Object.class));

        Object value = factory.createValueExpression(context, "${formatter.format('%.2f', validatedValue)}", Object.class)
            .getValue(context);

        assertEquals("0.50", value);
    }

    private static final class IntrospectionOnlyContext extends ELContext {

        private final ELResolver resolver = new IntrospectionELResolver();
        private final Map<String, ValueExpression> variables = new HashMap<>();

        void set(String name, ValueExpression expression) {
            variables.put(name, expression);
        }

        @Override
        public ELResolver getELResolver() {
            return resolver;
        }

        @Override
        public FunctionMapper getFunctionMapper() {
            return new FunctionMapper() {
                @Override
                public Method resolveFunction(String prefix, String localName) {
                    return null;
                }
            };
        }

        @Override
        public VariableMapper getVariableMapper() {
            return new VariableMapper() {
                @Override
                public ValueExpression resolveVariable(String variable) {
                    return variables.get(variable);
                }

                @Override
                public ValueExpression setVariable(String variable, ValueExpression expression) {
                    return variables.put(variable, expression);
                }
            };
        }
    }
}
