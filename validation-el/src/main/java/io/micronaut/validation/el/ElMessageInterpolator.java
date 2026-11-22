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
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.el.CompiledExpressionFactory;
import io.micronaut.el.interpreter.InterpretingELExpressionParser;
import io.micronaut.el.resolver.CommonELResolver;
import io.micronaut.el.resolver.IntrospectionELResolver;
import io.micronaut.el.resolver.StreamELResolver;
import io.micronaut.validation.validator.MessageAttributes;
import io.micronaut.validation.validator.messages.DefaultMessageInterpolator;
import io.micronaut.validation.validator.messages.DefaultMessageInterpolatorContext;
import io.micronaut.validation.validator.messages.InterpolatorLocaleResolver;
import jakarta.el.ExpressionFactory;
import jakarta.inject.Inject;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Internal Jakarta EL-backed message interpolator used only when the optional
 * EL module is present.
 *
 * <p>It replaces the default interpolator without being primary: an application bean that replaces the
 * default one too is not silently ignored, it has to declare which of the two it wants.</p>
 *
 * @since 5.3.0
 */
@Internal
@Singleton
@Replaces(DefaultMessageInterpolator.class)
@Requires(classes = CompiledExpressionFactory.class)
public final class ElMessageInterpolator implements MessageInterpolator {

    /**
     * The property enabling the evaluation of the expressions of a template a constraint validator builds with
     * {@link jakarta.validation.ConstraintValidatorContext#buildConstraintViolationWithTemplate(String)} in full: off by
     * default, as the template may carry what was validated. An expression written in such a template is then
     * evaluated only if it reads variables and the {@code formatter}, never a property or a method of a value; the
     * text a message bundle supplies for it is evaluated in full.
     */
    public static final String CUSTOM_VIOLATION_EXPRESSIONS = "micronaut.validator.custom-violation-expressions";

    private static final Logger LOG = LoggerFactory.getLogger(ElMessageInterpolator.class);

    private static final char ESCAPE = '\\';
    private static final char LEFT_BRACE = '{';
    private static final char RIGHT_BRACE = '}';
    private static final char DOLLAR = '$';
    private static final String FORMATTER = "formatter";
    // what follows the formatter in the one call a custom violation template may make
    private static final String FORMAT_CALL = "format";
    // the operators written as words, which may precede a parenthesis without calling anything
    private static final Set<String> OPERATOR_KEYWORDS = Set.of("and", "or", "not", "empty", "eq", "ne", "lt", "gt",
        "le", "ge", "div", "mod");
    private static final String VALIDATED_VALUE = "validatedValue";
    private static final String VALIDATED_PATH = "validatedPath";
    // how many bundle parameters one message may expand, all levels together
    private static final int MAX_EXPANDED_TOKENS = 1024;

    private final MessageSource messageSource;
    private final InterpolatorLocaleResolver interpolatorLocaleResolver;
    private final ExpressionFactory expressionFactory;
    private final ValidationMessageBundleLoader bundles;
    private final BeanIntrospector introspector;
    // whether the expressions of a template a constraint validator built are evaluated: it may carry what was validated
    private final boolean customViolationExpressions;

    /**
     * Creates an EL-backed message interpolator.
     *
     * @param messageSource The message source
     * @param interpolatorLocaleResolver The locale resolver
     */
    public ElMessageInterpolator(MessageSource messageSource,
                                 @Nullable InterpolatorLocaleResolver interpolatorLocaleResolver) {
        this(messageSource, interpolatorLocaleResolver, false);
    }

    /**
     * Creates an EL-backed message interpolator.
     *
     * @param messageSource The message source
     * @param interpolatorLocaleResolver The locale resolver
     * @param customViolationExpressions Whether the expressions of a template a constraint validator builds are
     *                                   evaluated, see {@code micronaut.validator.custom-violation-expressions}
     */
    @Inject
    public ElMessageInterpolator(MessageSource messageSource,
                                 @Nullable InterpolatorLocaleResolver interpolatorLocaleResolver,
                                 @Value("${" + CUSTOM_VIOLATION_EXPRESSIONS + ":false}") boolean customViolationExpressions) {
        this(messageSource, interpolatorLocaleResolver, applicationClassLoader(), customViolationExpressions);
    }

    ElMessageInterpolator(MessageSource messageSource,
                          @Nullable InterpolatorLocaleResolver interpolatorLocaleResolver,
                          ClassLoader classLoader) {
        this(messageSource, interpolatorLocaleResolver, classLoader, false);
    }

    ElMessageInterpolator(MessageSource messageSource,
                          @Nullable InterpolatorLocaleResolver interpolatorLocaleResolver,
                          ClassLoader classLoader,
                          boolean customViolationExpressions) {
        this.customViolationExpressions = customViolationExpressions;
        this.messageSource = messageSource;
        this.interpolatorLocaleResolver = interpolatorLocaleResolver == null ? OptionalLocaleResolver.INSTANCE : interpolatorLocaleResolver;
        this.bundles = new ValidationMessageBundleLoader(classLoader);
        this.introspector = bundles.introspector();
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
        Map<String, Object> attributes = new HashMap<>(MessageAttributes.of(context.getConstraintDescriptor()));
        // the message parameters the default interpolator offers besides the attributes: a {validatedValue} or
        // {validatedPath} parameter renders the same with or without EL. ${validatedValue} stays an expression
        Map<String, Object> parameters = new HashMap<>(2);
        parameters.put(VALIDATED_VALUE, context.getValidatedValue());
        if (context instanceof DefaultMessageInterpolatorContext interpolatorContext) {
            parameters.put(VALIDATED_PATH, interpolatorContext.getValidatorContext().getCurrentPath());
        }
        return interpolate(messageTemplate, MessageSource.MessageContext.of(locale, attributes), context, parameters);
    }

    private String interpolate(String template, MessageSource.MessageContext messageContext, Context interpolationContext,
                               Map<String, Object> parameters) {
        Locale locale = messageContext.getLocale();
        int[] budget = {MAX_EXPANDED_TOKENS};
        List<Token> tokens = expandUserBundles(tokenize(template, false), locale, new HashSet<>(), budget);
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
        tokens = expandUserBundles(provider, locale, new HashSet<>(), budget);
        StringBuilder result = new StringBuilder();
        Token previous = null;
        for (Token token : tokens) {
            Token preceding = previous;
            previous = token;
            if (token.kind() == Kind.TEXT) {
                result.append(token.value());
                continue;
            }
            Object attribute = messageContext.getVariables().get(token.value());
            // a parameter right after a dollar is what an escaped expression, \${validatedValue}, leaves: literal
            boolean escapedExpression = preceding != null && preceding.kind() == Kind.TEXT && preceding.value().endsWith("$");
            if (attribute == null && token.kind() == Kind.PARAMETER && !escapedExpression && parameters.containsKey(token.value())) {
                result.append(parameters.get(token.value()));
                continue;
            }
            if (attribute != null) {
                // Attribute values are final text, never input for another interpolation pass.
                if (token.kind() == Kind.EXPRESSION) {
                    result.append(DOLLAR);
                }
                result.append(attribute);
            } else if (token.kind() == Kind.EXPRESSION && !token.trusted() && !customViolationExpressions
                && interpolationContext instanceof DefaultMessageInterpolatorContext defaultContext && defaultContext.isCustomViolation()
                && !readsVariablesOnly(token.value())) {
                // a custom violation template may carry what was validated: what reads more than variables is left
                result.append(DOLLAR).append(LEFT_BRACE).append(token.value()).append(RIGHT_BRACE);
            } else if (token.kind() == Kind.EXPRESSION) {
                result.append(evaluateExpression(token.value(), interpolationContext, locale));
            } else {
                result.append(LEFT_BRACE).append(token.value()).append(RIGHT_BRACE);
            }
        }
        return result.toString();
    }

    private List<Token> expandUserBundles(List<Token> tokens, Locale locale, Set<String> expanding, int[] budget) {
        List<Token> result = new ArrayList<>();
        for (Token token : tokens) {
            if (token.kind() == Kind.TEXT || token.cycle()) {
                result.add(token);
                continue;
            }
            if (!expanding.add(token.value())) {
                // a cycle is cut once and for all: the later pass leaves the token as it stands
                result.add(new Token(token.kind(), token.value(), true, token.trusted()));
                continue;
            }
            if (--budget[0] < 0) {
                // the depth limit does not bound a message that doubles at every level
                throw new ValidationException("Validation message expands to more than " + MAX_EXPANDED_TOKENS + " parameters");
            }
            try {
                Optional<String> message = bundles.find(token.value(), locale);
                if (message.isEmpty()) {
                    result.add(token);
                } else {
                    if (expanding.size() > 64) {
                        throw new ValidationException("Validation message bundle nesting exceeds 64 levels");
                    }
                    result.addAll(expandUserBundles(replacement(token, message.get()), locale, expanding, budget));
                }
            } finally {
                expanding.remove(token.value());
            }
        }
        return result;
    }

    private static List<Token> replacement(Token token, String value) {
        // the text of a message bundle is trusted, whatever template it is reached from
        return tokenize(token.kind() == Kind.EXPRESSION ? "$" + value : value, true);
    }

    private static List<Token> tokenize(String template, boolean trusted) {
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
                        tokens.add(new Token(Kind.TEXT, text.toString(), false, trusted));
                        text.setLength(0);
                    }
                    tokens.add(new Token(expression ? Kind.EXPRESSION : Kind.PARAMETER, template.substring(start, end), false, trusted));
                    i = end;
                    continue;
                }
            }
            text.append(current);
        }
        if (!text.isEmpty()) {
            tokens.add(new Token(Kind.TEXT, text.toString(), false, trusted));
        }
        return tokens;
    }

    private String evaluateExpression(String expression, Context context, Locale locale) {
        ValidationELContext elContext = new ValidationELContext(introspector);
        for (Map.Entry<String, Object> entry : MessageAttributes.of(context.getConstraintDescriptor()).entrySet()) {
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

    /**
     * Whether an expression reads only variables, literals and operators, and calls nothing but
     * {@code formatter.format}: no property, index or method of a value, which is what an expression of a
     * custom violation template is limited to, as the {@code VARIABLES} level of the reference implementation
     * limits it. A property access is a dot after a name, an index an opening bracket, a call an opening
     * parenthesis after a name; a dot in a number and the text of a string literal are neither.
     *
     * @param expression The expression, without its delimiters
     * @return Whether it reads variables only
     */
    static boolean readsVariablesOnly(String expression) {
        char quote = 0;
        for (int i = 0; i < expression.length(); i++) {
            char current = expression.charAt(i);
            if (quote != 0) {
                if (current == ESCAPE) {
                    i++;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '[') {
                return false;
            } else if (current == '.') {
                String name = nameBefore(expression, i);
                boolean number = name.isEmpty()
                    ? i + 1 < expression.length() && Character.isDigit(expression.charAt(i + 1))
                    : Character.isDigit(name.charAt(0));
                if (!number && !(FORMATTER.equals(name) && isFormatCall(expression, i + 1))) {
                    return false;
                }
            } else if (current == '(') {
                int end = i;
                while (end > 0 && Character.isWhitespace(expression.charAt(end - 1))) {
                    end--;
                }
                String name = nameBefore(expression, end);
                // the format call follows a dot, which is checked to follow the formatter
                boolean formatCall = FORMAT_CALL.equals(name) && end > name.length() && expression.charAt(end - name.length() - 1) == '.';
                if (!name.isEmpty() && !OPERATOR_KEYWORDS.contains(name) && !formatCall) {
                    return false;
                }
            }
        }
        return true;
    }

    // whether the name at the offset is the format method of the formatter, and is called
    private static boolean isFormatCall(String expression, int offset) {
        int end = offset + FORMAT_CALL.length();
        if (!expression.startsWith(FORMAT_CALL, offset) || (end < expression.length() && isNamePart(expression.charAt(end)))) {
            return false;
        }
        while (end < expression.length() && Character.isWhitespace(expression.charAt(end))) {
            end++;
        }
        return end < expression.length() && expression.charAt(end) == '(';
    }

    private static String nameBefore(String expression, int end) {
        int start = end;
        while (start > 0 && isNamePart(expression.charAt(start - 1))) {
            start--;
        }
        return expression.substring(start, end);
    }

    private static boolean isNamePart(char c) {
        return Character.isJavaIdentifierPart(c);
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
         * The largest width or precision a format may ask for: a template carrying what was validated would
         * otherwise pad a message until the memory runs out.
         */
        static final int MAX_WIDTH = 1024;
        private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%(\\d+\\$)?[-#+ 0,(<]*(\\d+)?(\\.(\\d+))?");

        /**
         * Formats a message with the interpolation locale.
         *
         * @param format The format
         * @param args The arguments
         * @return The formatted message
         * @throws IllegalArgumentException if a width or precision of the format is above {@value #MAX_WIDTH}
         */
        public String format(String format, Object... args) {
            Matcher specifier = FORMAT_SPECIFIER.matcher(format);
            while (specifier.find()) {
                checkWidth(format, specifier.group(2));
                checkWidth(format, specifier.group(4));
            }
            try (Formatter formatter = new Formatter(locale)) {
                return formatter.format(format, args).toString();
            }
        }

        private static void checkWidth(String format, @Nullable String digits) {
            // more digits than the bound has cannot be within it, and may not fit an int
            if (digits != null && (digits.length() > 4 || Integer.parseInt(digits) > MAX_WIDTH)) {
                throw new IllegalArgumentException("A width or precision above " + MAX_WIDTH + " in the format: " + format);
            }
        }
    }

    private enum Kind { TEXT, PARAMETER, EXPRESSION }

    /**
     * A token of a message.
     *
     * @param kind  What it is
     * @param value Its text, or the name it refers to
     * @param cycle Whether expanding it was cut as a cycle, so that no later pass expands it
     * @param trusted Whether it comes from the text of a message bundle rather than from the template itself
     */
    private record Token(Kind kind, String value, boolean cycle, boolean trusted) {
    }

    private enum OptionalLocaleResolver implements InterpolatorLocaleResolver {
        INSTANCE;

        @Override
        public Optional<Locale> resolve() {
            return Optional.empty();
        }
    }
}
