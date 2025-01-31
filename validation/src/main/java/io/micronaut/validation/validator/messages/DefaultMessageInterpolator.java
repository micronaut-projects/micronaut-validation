/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.validation.validator.messages;

import io.micronaut.context.MessageSource;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.util.ArgumentUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.MessageInterpolator;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The default error messages.
 *
 * @author graemerocher
 * @since 1.2
 */
@Singleton
public class DefaultMessageInterpolator implements MessageInterpolator {

    private static final char QUOT = '\\';
    private static final char L_BRACE = '{';
    private static final char R_BRACE = '}';
    private static final char DOLL_BRACE = '$';

    @Nullable
    private final InterpolatorLocaleResolver interpolatorLocaleResolver;

    private final MessageSource messageSource;

    /**
     *
     * @param messageSource Message Source
     * @param interpolatorLocaleResolver Interpolator Locale Resolver
     */
    @Inject
    public DefaultMessageInterpolator(MessageSource messageSource,
                                      @Nullable InterpolatorLocaleResolver interpolatorLocaleResolver) {
        this.messageSource = messageSource;
        this.interpolatorLocaleResolver = interpolatorLocaleResolver;
    }

    /**
     *
     * @param messageSource Message Source
     * @deprecated Use {@link #DefaultMessageInterpolator(MessageSource, InterpolatorLocaleResolver)} instead.
     */
    @Deprecated(forRemoval = true, since = "4.9.0")
    public DefaultMessageInterpolator(MessageSource messageSource) {
        this(messageSource, Optional::empty);
    }

    private String interpolate(@NonNull String template, @NonNull MessageSource.MessageContext context) {
        ArgumentUtils.requireNonNull("template", template);
        ArgumentUtils.requireNonNull("context", context);

        StringBuilder messageBuilder = new StringBuilder();
        StringBuilder variableBuilder = new StringBuilder();
        StringBuilder builder = messageBuilder;
        boolean isVariable = false;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == QUOT && i + 1 < template.length()) {
                c = template.charAt(++i);
                if (c == L_BRACE) {
                    builder.append(L_BRACE);
                } else if (c == R_BRACE) {
                    builder.append(R_BRACE);
                } else if (c == DOLL_BRACE) {
                    builder.append(DOLL_BRACE);
                } else {
                    builder.append(QUOT);
                    builder.append(c);
                }
                continue;
            }
            if (c == L_BRACE) {
                if (!isVariable) {
                    isVariable = true;
                    builder = variableBuilder;
                } else {
                    builder.append(c);
                }
            } else if (c == R_BRACE) {
                if (isVariable) {
                    builder = messageBuilder;
                    isVariable = false;
                    String variableName = variableBuilder.toString();
                    variableBuilder.setLength(0);
                    Object variableValue = context.getVariables().get(variableName);
                    if (variableValue == null) {
                        variableValue = messageSource.getMessage(variableName, context).orElse(null);
                    }
                    if (variableValue == null) {
                        builder.append(L_BRACE).append(variableName).append(R_BRACE);
                    } else {
                        builder.append(variableValue);
                    }
                } else {
                    builder.append(c);
                }
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    @Override
    public String interpolate(String messageTemplate, Context context) {
        Locale locale = interpolatorLocaleResolver != null
            ? interpolatorLocaleResolver.resolve().orElseGet(Locale::getDefault)
            : Locale.getDefault();
        return interpolate(messageTemplate, context, locale);
    }

    @Override
    public String interpolate(String messageTemplate, Context context, Locale locale) {
        Map<String, Object> attributes = new HashMap<>(context.getConstraintDescriptor().getAttributes());
        attributes.put("validatedValue", context.getValidatedValue());
        return interpolate(messageTemplate, MessageSource.MessageContext.of(locale, attributes));
    }
}
