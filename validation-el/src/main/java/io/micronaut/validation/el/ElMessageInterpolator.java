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
import io.micronaut.core.annotation.Introspected;
import io.micronaut.el.CompiledELContext;
import io.micronaut.el.CompiledExpressionFactory;
import io.micronaut.validation.validator.messages.DefaultMessageInterpolator;
import io.micronaut.validation.validator.messages.InterpolatorLocaleResolver;
import jakarta.el.ELContext;
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
 * Internal Jakarta EL-backed message interpolator used only when the optional
 * EL module is present.
 *
 * <p>The expressions are created through {@link ExpressionFactory#newInstance()}. When the factory is the
 * {@link CompiledExpressionFactory} of {@code micronaut-expression-language}, an expression that the
 * annotation processor of {@code micronaut-validation-el-processor} compiled is returned without being
 * parsed, and the resolution goes through the bean introspections rather than through the reflective
 * {@code jakarta.el.BeanELResolver}.</p>
 *
 * @since 5.1
 */
@Internal
@Singleton
@Primary
@Replaces(DefaultMessageInterpolator.class)
@Requires(classes = ExpressionFactory.class)
public final class ElMessageInterpolator implements MessageInterpolator {

    private final MessageSource messageSource;
    private final InterpolatorLocaleResolver interpolatorLocaleResolver;
    private final ExpressionFactory expressionFactory;

    /**
     * Creates an EL-backed message interpolator.
     *
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
        String resolvedTemplate = MessageTemplates.resolveParameters(
            template, name -> resolveParameter(name, messageContext, locale));
        return MessageTemplates.interpolateExpressions(
            resolvedTemplate, expression -> evaluateExpression(expression, interpolationContext, locale));
    }

    private Optional<?> resolveParameter(String variableName, MessageSource.MessageContext messageContext, Locale locale) {
        Optional<String> userMessage = findUserMessage(variableName, locale);
        if (userMessage.isPresent()) {
            return userMessage;
        }
        Optional<String> providerMessage = messageSource.getMessage(variableName, messageContext);
        if (providerMessage.isPresent()) {
            return providerMessage;
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

    /**
     * Evaluates one expression of a template.
     *
     * @param expression The expression string, {@code ${...}} included, exactly as the annotation processor
     *                   compiled it
     */
    private String evaluateExpression(String expression, Context context, Locale locale) {
        ELContext elContext = createElContext();
        for (Map.Entry<String, Object> entry : context.getConstraintDescriptor().getAttributes().entrySet()) {
            setVariable(elContext, entry.getKey(), entry.getValue());
        }
        setVariable(elContext, "validatedValue", context.getValidatedValue());
        setVariable(elContext, "groups", context.getConstraintDescriptor().getGroups().toArray(Class<?>[]::new));
        setVariable(elContext, "payload", context.getConstraintDescriptor().getPayload().toArray(Class<?>[]::new));
        setVariable(elContext, "formatter", new LocaleFormatter(locale));
        try {
            Object value = expressionFactory.createValueExpression(elContext, expression, Object.class).getValue(elContext);
            return value == null ? "" : value.toString();
        } catch (RuntimeException e) {
            return expression;
        }
    }

    private void setVariable(ELContext elContext, String name, @Nullable Object value) {
        elContext.getVariableMapper().setVariable(name, expressionFactory.createValueExpression(value, Object.class));
    }

    /**
     * The compiled expressions resolve the properties through the bean introspections, which
     * {@link CompiledELContext} installs and {@link StandardELContext} does not, so the context follows the
     * factory rather than being fixed.
     */
    private ELContext createElContext() {
        if (expressionFactory instanceof CompiledExpressionFactory) {
            return new CompiledELContext();
        }
        return new StandardELContext(expressionFactory);
    }

    /**
     * Locale-aware formatter exposed to Jakarta EL expressions as {@code formatter}.
     *
     * <p>The type is introspected so that its properties are read through the generated introspection.
     * {@code format} is deliberately left out of the introspection: it is a varargs method, and
     * {@code IntrospectionELResolver} coerces the arguments to the declared parameter types without expanding
     * a varargs parameter first, so an introspected {@code format} fails to resolve. Leaving it out sends the
     * invocation to the reflective resolver of the specification, which does expand it. Annotate it with
     * {@code @Executable} once micronaut-expression-language handles varargs.</p>
     *
     * @param locale The locale
     * @since 5.1
     */
    @Internal
    @Introspected
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

    private enum OptionalLocaleResolver implements InterpolatorLocaleResolver {
        INSTANCE;

        @Override
        public Optional<Locale> resolve() {
            return Optional.empty();
        }
    }
}
