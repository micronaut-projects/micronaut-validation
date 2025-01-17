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
import io.micronaut.core.util.ArgumentUtils;
import jakarta.inject.Singleton;
import jakarta.validation.MessageInterpolator;

import java.util.HashMap;
import java.util.Locale;

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

    private final MessageSource messageSource;

    public DefaultMessageInterpolator(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    private String interpolate(@NonNull String template, @NonNull MessageSource.MessageContext context) {
        ArgumentUtils.requireNonNull("template", template);
        ArgumentUtils.requireNonNull("context", context);

        var messageBuilder = new StringBuilder();
        var variableBuilder = new StringBuilder();
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
        return interpolate(messageTemplate, context, Locale.ENGLISH);
    }

    @Override
    public String interpolate(String messageTemplate, Context context, Locale locale) {
        var attributes = new HashMap<>(context.getConstraintDescriptor().getAttributes());
        attributes.put("validatedValue", context.getValidatedValue());
        if (context instanceof DefaultMessageInterpolatorContext interpolatorContext) {
            attributes.put("validatedPath", interpolatorContext.getValidatorContext().getCurrentPath());
        }
        return interpolate(messageTemplate, MessageSource.MessageContext.of(locale, attributes));
    }
}
