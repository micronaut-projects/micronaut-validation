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
package io.micronaut.validation.el;

import io.micronaut.context.MessageSource;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.validation.validator.messages.DefaultMessageInterpolator;
import io.micronaut.validation.validator.messages.InterpolatorLocaleResolver;
import jakarta.el.ELException;
import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;
import jakarta.inject.Singleton;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ValidationException;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Jakarta EL-backed message interpolator.
 *
 * @since 5.1
 */
@Singleton
@Primary
@Replaces(DefaultMessageInterpolator.class)
@Requires(classes = ExpressionFactory.class)
public final class ElMessageInterpolator implements MessageInterpolator {

    private static final char ESCAPE = '\\';
    private static final char LEFT_BRACE = '{';
    private static final char RIGHT_BRACE = '}';
    private static final char DOLLAR = '$';

    private final MessageSource messageSource;
    private final InterpolatorLocaleResolver interpolatorLocaleResolver;
    private final ExpressionFactory expressionFactory;

    /**
     * @param messageSource The message source
     * @param interpolatorLocaleResolver The locale resolver
     */
    public ElMessageInterpolator(MessageSource messageSource,
                                 @Nullable InterpolatorLocaleResolver interpolatorLocaleResolver) {
        this.messageSource = messageSource;
        this.interpolatorLocaleResolver = interpolatorLocaleResolver == null ? OptionalLocaleResolver.INSTANCE : interpolatorLocaleResolver;
        this.expressionFactory = ExpressionFactory.newInstance();
    }

    @Override
    public String interpolate(String messageTemplate, Context context) {
        Locale locale = interpolatorLocaleResolver.resolve().orElseGet(Locale::getDefault);
        return interpolate(messageTemplate, context, locale);
    }

    @Override
    public String interpolate(String messageTemplate, Context context, Locale locale) {
        Map<String, Object> attributes = new HashMap<>(context.getConstraintDescriptor().getAttributes());
        attributes.put("validatedValue", context.getValidatedValue());
        return interpolate(messageTemplate, MessageSource.MessageContext.of(locale, attributes), context);
    }

    private String interpolate(String template, MessageSource.MessageContext messageContext, Context interpolationContext) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < template.length(); i++) {
            char current = template.charAt(i);
            if (current == ESCAPE && i + 1 < template.length()) {
                result.append(template.charAt(++i));
                continue;
            }
            if (current == DOLLAR && i + 1 < template.length() && template.charAt(i + 1) == LEFT_BRACE) {
                int end = findExpressionEnd(template, i + 2);
                if (end > -1) {
                    result.append(evaluateExpression(template.substring(i + 2, end), interpolationContext));
                    i = end;
                    continue;
                }
            }
            if (current == LEFT_BRACE) {
                int end = findExpressionEnd(template, i + 1);
                if (end > -1) {
                    String variableName = template.substring(i + 1, end);
                    Object variableValue = messageContext.getVariables().get(variableName);
                    if (variableValue == null) {
                        variableValue = messageSource.getMessage(variableName, messageContext).orElse(null);
                    }
                    if (variableValue != null) {
                        result.append(variableValue);
                    } else {
                        result.append(LEFT_BRACE).append(variableName).append(RIGHT_BRACE);
                    }
                    i = end;
                    continue;
                }
            }
            result.append(current);
        }
        return result.toString();
    }

    private Object evaluateExpression(String expression, Context context) {
        StandardELContext elContext = new StandardELContext(expressionFactory);
        for (Map.Entry<String, Object> entry : context.getConstraintDescriptor().getAttributes().entrySet()) {
            elContext.getVariableMapper().setVariable(
                entry.getKey(),
                expressionFactory.createValueExpression(entry.getValue(), Object.class)
            );
        }
        elContext.getVariableMapper().setVariable(
            "validatedValue",
            expressionFactory.createValueExpression(context.getValidatedValue(), Object.class)
        );
        try {
            Object value = expressionFactory.createValueExpression(elContext, "${" + expression + "}", Object.class).getValue(elContext);
            return value == null ? "" : value;
        } catch (ELException e) {
            throw new ValidationException("Exception during Jakarta EL message interpolation", e);
        }
    }

    private static int findExpressionEnd(String template, int offset) {
        for (int i = offset; i < template.length(); i++) {
            char current = template.charAt(i);
            if (current == ESCAPE && i + 1 < template.length()) {
                i++;
                continue;
            }
            if (current == RIGHT_BRACE) {
                return i;
            }
        }
        return -1;
    }

    private enum OptionalLocaleResolver implements InterpolatorLocaleResolver {
        INSTANCE;

        @Override
        public Optional<Locale> resolve() {
            return Optional.empty();
        }
    }
}
