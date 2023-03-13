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
import io.micronaut.context.StaticMessageSource;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.util.ArgumentUtils;
import jakarta.inject.Singleton;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The default error messages.
 *
 * @author graemerocher
 * @since 1.2
 */
@Singleton
public class DefaultValidationMessages implements MessageInterpolator {

    private static final char QUOT = '\\';
    private static final char L_BRACE = '{';
    private static final char R_BRACE = '}';
    private static final char DOLL_BRACE = '$';

    /**
     * The message suffix to use.
     */
    private static final String MESSAGE_SUFFIX = ".message";

    private final StaticMessageSource messageSource = new StaticMessageSource();

    /**
     * Constructs the default error messages.
     */
    public DefaultValidationMessages() {
        messageSource.addMessage(AssertTrue.class.getName() + MESSAGE_SUFFIX, "must be true");
        messageSource.addMessage(AssertFalse.class.getName() + MESSAGE_SUFFIX, "must be false");
        messageSource.addMessage(DecimalMax.class.getName() + MESSAGE_SUFFIX, "must be less than or equal to {value}");
        messageSource.addMessage(DecimalMin.class.getName() + MESSAGE_SUFFIX, "must be greater than or equal to {value}");
        messageSource.addMessage(Digits.class.getName() + MESSAGE_SUFFIX, "numeric value out of bounds (<{integer} digits>.<{fraction} digits> expected)");
        messageSource.addMessage(Email.class.getName() + MESSAGE_SUFFIX, "must be a well-formed email address");
        messageSource.addMessage(Future.class.getName() + MESSAGE_SUFFIX, "must be a future date");
        messageSource.addMessage(FutureOrPresent.class.getName() + MESSAGE_SUFFIX, "must be a date in the present or in the future");
        messageSource.addMessage(Max.class.getName() + MESSAGE_SUFFIX, "must be less than or equal to {value}");
        messageSource.addMessage(Min.class.getName() + MESSAGE_SUFFIX, "must be greater than or equal to {value}");
        messageSource.addMessage(Negative.class.getName() + MESSAGE_SUFFIX, "must be less than 0");
        messageSource.addMessage(NegativeOrZero.class.getName() + MESSAGE_SUFFIX, "must be less than or equal to 0");
        messageSource.addMessage(NotBlank.class.getName() + MESSAGE_SUFFIX, "must not be blank");
        messageSource.addMessage(NotEmpty.class.getName() + MESSAGE_SUFFIX, "must not be empty");
        messageSource.addMessage(NotNull.class.getName() + MESSAGE_SUFFIX, "must not be null");
        messageSource.addMessage(Null.class.getName() + MESSAGE_SUFFIX, "must be null");
        messageSource.addMessage(Past.class.getName() + MESSAGE_SUFFIX, "must be a past date");
        messageSource.addMessage(PastOrPresent.class.getName() + MESSAGE_SUFFIX, "must be a date in the past or in the present");
        messageSource.addMessage(Pattern.class.getName() + MESSAGE_SUFFIX, "must match \"{regexp}\"");

        messageSource.addMessage(Positive.class.getName() + MESSAGE_SUFFIX, "must be greater than 0");
        messageSource.addMessage(PositiveOrZero.class.getName() + MESSAGE_SUFFIX, "must be greater than or equal to 0");
        messageSource.addMessage(Size.class.getName() + MESSAGE_SUFFIX, "size must be between {min} and {max}");

        messageSource.addMessage(Introspected.class.getName() + MESSAGE_SUFFIX, "Cannot validate {type}. No bean introspection present. Please add @Introspected to the class and ensure Micronaut annotation processing is enabled");
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
        return interpolate(messageTemplate, context, Locale.ENGLISH);
    }

    @Override
    public String interpolate(String messageTemplate, Context context, Locale locale) {
        Map<String, Object> attributes = new HashMap<>(context.getConstraintDescriptor().getAttributes());
        attributes.put("validatedValue", context.getValidatedValue());
        return interpolate(messageTemplate, MessageSource.MessageContext.of(locale, attributes));
    }
}
