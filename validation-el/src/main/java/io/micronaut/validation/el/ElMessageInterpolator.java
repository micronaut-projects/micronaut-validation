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
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.el.CompiledExpressionFactory;
import io.micronaut.el.interpreter.InterpretingELExpressionParser;
import io.micronaut.el.resolver.CommonELResolver;
import io.micronaut.el.resolver.IntrospectionELResolver;
import io.micronaut.el.resolver.StreamELResolver;
import io.micronaut.validation.validator.messages.DefaultMessageInterpolator;
import io.micronaut.validation.validator.messages.InterpolatorLocaleResolver;
import jakarta.el.ExpressionFactory;
import jakarta.inject.Singleton;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ValidationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Internal Jakarta EL-backed message interpolator used only when the optional
 * EL module is present.
 *
 * @since 5.3.0
 */
@Internal
@Singleton
@Primary
@Replaces(DefaultMessageInterpolator.class)
@Requires(classes = CompiledExpressionFactory.class)
public final class ElMessageInterpolator implements MessageInterpolator {

    private static final Logger LOG = LoggerFactory.getLogger(ElMessageInterpolator.class);

    private static final char ESCAPE = '\\';
    private static final char LEFT_BRACE = '{';
    private static final char RIGHT_BRACE = '}';
    private static final char DOLLAR = '$';

    private final MessageSource messageSource;
    private final InterpolatorLocaleResolver interpolatorLocaleResolver;
    private final ExpressionFactory expressionFactory;
    private final ValidationMessageBundleLoader bundles;
    private final BeanIntrospector introspector;

    /**
     * Creates an EL-backed message interpolator.
     *
     * @param messageSource The message source
     * @param interpolatorLocaleResolver The locale resolver
     */
    public ElMessageInterpolator(MessageSource messageSource,
                                 @Nullable InterpolatorLocaleResolver interpolatorLocaleResolver) {
        this(messageSource, interpolatorLocaleResolver, applicationClassLoader());
    }

    ElMessageInterpolator(MessageSource messageSource,
                          @Nullable InterpolatorLocaleResolver interpolatorLocaleResolver,
                          ClassLoader classLoader) {
        this.messageSource = messageSource;
        this.interpolatorLocaleResolver = interpolatorLocaleResolver == null ? OptionalLocaleResolver.INSTANCE : interpolatorLocaleResolver;
        this.bundles = new ValidationMessageBundleLoader(classLoader);
        this.introspector = BeanIntrospector.forClassLoader(classLoader);
        this.expressionFactory = new CompiledExpressionFactory(
            ElExpressionSources.load(classLoader),
            new InterpretingELExpressionParser(List.of(
                new IntrospectionELResolver(introspector, true), new CommonELResolver(),
                new StreamELResolver(), new ValidationFormatterExecutor())));
    }

    private static ClassLoader applicationClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? ElMessageInterpolator.class.getClassLoader() : loader;
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
        List<Token> tokens = expandUserBundles(tokenize(template), locale, new HashSet<>());
        List<Token> provider = new ArrayList<>();
        for (Token token : tokens) {
            Optional<String> replacement = token.kind() == Kind.TEXT ? Optional.empty()
                : messageSource.getRawMessage(token.value(), messageContext);
            if (replacement.isPresent()) {
                provider.addAll(replacement(token, replacement.get()));
            } else {
                provider.add(token);
            }
        }
        tokens = expandUserBundles(provider, locale, new HashSet<>());
        StringBuilder result = new StringBuilder();
        for (Token token : tokens) {
            if (token.kind() == Kind.TEXT) {
                result.append(token.value());
                continue;
            }
            Object attribute = messageContext.getVariables().get(token.value());
            if (attribute != null) {
                // Attribute values are final text, never input for another interpolation pass.
                if (token.kind() == Kind.EXPRESSION) {
                    result.append(DOLLAR);
                }
                result.append(attribute);
            } else if (token.kind() == Kind.EXPRESSION) {
                result.append(evaluateExpression(token.value(), interpolationContext, locale));
            } else {
                result.append(LEFT_BRACE).append(token.value()).append(RIGHT_BRACE);
            }
        }
        return result.toString();
    }

    private List<Token> expandUserBundles(List<Token> tokens, Locale locale, Set<String> expanding) {
        List<Token> result = new ArrayList<>();
        for (Token token : tokens) {
            if (token.kind() == Kind.TEXT || !expanding.add(token.value())) {
                result.add(token);
                continue;
            }
            try {
                Optional<String> message = bundles.find(token.value(), locale);
                if (message.isEmpty()) {
                    result.add(token);
                } else {
                    if (expanding.size() > 64) {
                        throw new ValidationException("Validation message bundle nesting exceeds 64 levels");
                    }
                    result.addAll(expandUserBundles(replacement(token, message.get()), locale, expanding));
                }
            } finally {
                expanding.remove(token.value());
            }
        }
        return result;
    }

    private static List<Token> replacement(Token token, String value) {
        return tokenize(token.kind() == Kind.EXPRESSION ? "$" + value : value);
    }

    private static List<Token> tokenize(String template) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < template.length(); i++) {
            char current = template.charAt(i);
            if (current == ESCAPE && i + 1 < template.length()) {
                char next = template.charAt(i + 1);
                if (next == ESCAPE || next == LEFT_BRACE || next == RIGHT_BRACE || next == DOLLAR) {
                    text.append(next);
                    i++;
                    continue;
                }
            }
            boolean expression = current == DOLLAR && i + 1 < template.length() && template.charAt(i + 1) == LEFT_BRACE;
            if (expression || current == LEFT_BRACE) {
                int start = i + (expression ? 2 : 1);
                int end = findExpressionEnd(template, start);
                if (end >= 0) {
                    if (!text.isEmpty()) {
                        tokens.add(new Token(Kind.TEXT, text.toString()));
                        text.setLength(0);
                    }
                    tokens.add(new Token(expression ? Kind.EXPRESSION : Kind.PARAMETER, template.substring(start, end)));
                    i = end;
                    continue;
                }
            }
            text.append(current);
        }
        if (!text.isEmpty()) {
            tokens.add(new Token(Kind.TEXT, text.toString()));
        }
        return tokens;
    }

    private String evaluateExpression(String expression, Context context, Locale locale) {
        ValidationELContext elContext = new ValidationELContext(introspector);
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
            // the expression is left as written, the way an unresolved message parameter is; what it failed
            // on - a type without an introspection as much as a typo - would otherwise be invisible
            if (LOG.isDebugEnabled()) {
                LOG.debug("Cannot evaluate the EL expression '{}' of a validation message", expression, e);
            }
            return "${" + expression + "}";
        }
    }

    private static int findExpressionEnd(String template, int offset) {
        int nested = 0;
        char quote = 0;
        for (int i = offset; i < template.length(); i++) {
            char current = template.charAt(i);
            if (current == ESCAPE && i + 1 < template.length()) {
                i++;
            } else if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == LEFT_BRACE) {
                nested++;
            } else if (current == RIGHT_BRACE) {
                if (nested == 0) {
                    return i;
                }
                nested--;
            }
        }
        return -1;
    }

    /**
     * Locale-aware formatter exposed to Jakarta EL expressions as {@code formatter}.
     *
     * @param locale The locale
     * @since 5.3.0
     */
    @Internal
    public record LocaleFormatter(Locale locale) {

        /**
         * Formats a message with the interpolation locale.
         *
         * @param format The format
         * @param args The arguments
         * @return The formatted message
         */
        public String format(String format, Object... args) {
            try (Formatter formatter = new Formatter(locale)) {
                return formatter.format(format, args).toString();
            }
        }
    }

    private enum Kind { TEXT, PARAMETER, EXPRESSION }

    private record Token(Kind kind, String value) { }

    private enum OptionalLocaleResolver implements InterpolatorLocaleResolver {
        INSTANCE;

        @Override
        public Optional<Locale> resolve() {
            return Optional.empty();
        }
    }
}
