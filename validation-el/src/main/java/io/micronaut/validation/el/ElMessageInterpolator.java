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
import io.micronaut.core.annotation.Internal;
import io.micronaut.validation.validator.messages.DefaultMessageInterpolator;
import io.micronaut.validation.validator.messages.InterpolatorLocaleResolver;
import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;
import jakarta.inject.Singleton;
import jakarta.validation.MessageInterpolator;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Formatter;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;

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
    private static final int MAX_RECURSION = 10;

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
        return interpolate(messageTemplate, MessageSource.MessageContext.of(locale, attributes), context);
    }

    private String interpolate(String template, MessageSource.MessageContext messageContext, Context interpolationContext) {
        Locale locale = messageContext.getLocale();
        String resolvedTemplate = template;
        for (int i = 0; i < MAX_RECURSION; i++) {
            String resolved = interpolateParameters(resolvedTemplate, messageContext);
            if (resolved.equals(resolvedTemplate)) {
                break;
            }
            resolvedTemplate = resolved;
        }
        return interpolateExpressions(resolvedTemplate, interpolationContext, locale);
    }

    private String interpolateParameters(String template, MessageSource.MessageContext messageContext) {
        Locale locale = messageContext.getLocale();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < template.length(); i++) {
            char current = template.charAt(i);
            if (current == ESCAPE && i + 1 < template.length()) {
                result.append(template.charAt(++i));
                continue;
            }
            if (current == LEFT_BRACE) {
                int end = findExpressionEnd(template, i + 1);
                if (end > -1) {
                    String variableName = template.substring(i + 1, end);
                    result.append(resolveParameter(variableName, messageContext, locale)
                        .orElse(LEFT_BRACE + variableName + String.valueOf(RIGHT_BRACE)));
                    i = end;
                    continue;
                }
            }
            result.append(current);
        }
        return result.toString();
    }

    private String interpolateExpressions(String template, Context interpolationContext, Locale locale) {
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
                    result.append(evaluateExpression(template.substring(i + 2, end), interpolationContext, locale));
                    i = end;
                    continue;
                }
            }
            result.append(current);
        }
        return result.toString();
    }

    private Optional<Object> resolveParameter(String variableName, MessageSource.MessageContext messageContext, Locale locale) {
        Optional<String> userMessage = findUserMessage(variableName, locale);
        if (userMessage.isPresent()) {
            return Optional.of(userMessage.get());
        }
        Optional<String> providerMessage = messageSource.getMessage(variableName, messageContext);
        if (providerMessage.isPresent()) {
            return Optional.of(providerMessage.get());
        }
        return Optional.ofNullable(messageContext.getVariables().get(variableName));
    }

    private static Optional<String> findUserMessage(String variableName, Locale locale) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle("ValidationMessages", locale, Thread.currentThread().getContextClassLoader());
            if (bundle.containsKey(variableName)) {
                return Optional.of(bundle.getString(variableName));
            }
        } catch (MissingResourceException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private String evaluateExpression(String expression, Context context, Locale locale) {
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
        elContext.getVariableMapper().setVariable(
            "groups",
            expressionFactory.createValueExpression(context.getConstraintDescriptor().getGroups().toArray(Class<?>[]::new), Object.class)
        );
        elContext.getVariableMapper().setVariable(
            "payload",
            expressionFactory.createValueExpression(context.getConstraintDescriptor().getPayload().toArray(Class<?>[]::new), Object.class)
        );
        elContext.getVariableMapper().setVariable(
            "formatter",
            expressionFactory.createValueExpression(new LocaleFormatter(locale), Object.class)
        );
        try {
            Object value = expressionFactory.createValueExpression(elContext, "${" + expression + "}", Object.class).getValue(elContext);
            return value == null ? "" : value.toString();
        } catch (RuntimeException e) {
            return "${" + expression + "}";
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

    /**
     * Locale-aware formatter exposed to Jakarta EL expressions as {@code formatter}.
     *
     * @param locale The locale
     * @since 5.1
     */
    @Internal
    public record LocaleFormatter(Locale locale) {

        public String format(String format, Object... args) {
            try (Formatter formatter = new Formatter(locale)) {
                return formatter.format(format, args).toString();
            }
        }
    }

    private enum OptionalLocaleResolver implements InterpolatorLocaleResolver {
        INSTANCE;

        @Override
        public Optional<Locale> resolve() {
            return Optional.empty();
        }
    }
}
